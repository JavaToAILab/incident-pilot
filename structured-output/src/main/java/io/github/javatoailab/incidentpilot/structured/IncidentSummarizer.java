package io.github.javatoailab.incidentpilot.structured;

import io.github.javatoailab.incidentpilot.context.Alert;
import io.github.javatoailab.incidentpilot.llm.ChatRequest;
import io.github.javatoailab.incidentpilot.llm.ChatResponse;
import io.github.javatoailab.incidentpilot.llm.LlmClient;
import io.github.javatoailab.incidentpilot.llm.LlmException;
import io.github.javatoailab.incidentpilot.prompt.IncidentSummaryPrompt;
import io.github.javatoailab.incidentpilot.prompt.PromptRegistry;
import io.github.javatoailab.incidentpilot.prompt.PromptTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Turns an alert and its logs into an {@link IncidentSummary}, or says plainly why it could not.
 *
 * <p>The flow: ask once; check the reply against the JSON contract and against the input; if it fails,
 * ask once more with the list of problems; if that fails too, give up and return
 * {@link SummaryOutcome.Rejected}. One repair, never a loop: a model that broke the contract twice
 * in a row is not going to be talked into it by a third call, and each call costs time during an incident.
 */
public final class IncidentSummarizer {

    public static final String REPAIR_PROMPT_ID = "incident-summary-repair";

    /** The previous reply is quoted back in the repair request; cap it so one runaway answer can't blow the budget. */
    static final int MAX_PREVIOUS_REPLY_CHARS = 4000;

    private static final System.Logger LOG = System.getLogger(IncidentSummarizer.class.getName());

    private final LlmClient llm;
    private final IncidentSummaryPrompt prompt;
    private final PromptTemplate repairTemplate;
    private final boolean sendSchema;
    private final Double temperature;
    private final JsonContract<IncidentSummary> contract = new JsonContract<>(IncidentSummary.class);
    private final IncidentSummaryChecks checks = new IncidentSummaryChecks(IncidentSummaryPrompt.DEFAULT_MAX_SUMMARY_WORDS);

    /**
     * @param sendSchema true to also send the record's JSON Schema, so the model server constrains the
     *                   reply's shape; the same checks run either way
     */
    public IncidentSummarizer(LlmClient llm, PromptRegistry registry, IncidentSummaryPrompt prompt, boolean sendSchema) {
        this(llm, registry, prompt, sendSchema, null);
    }

    /**
     * @param temperature overrides the request's temperature (0.0 by default). Only the live report
     *                    raises it, to sample how often the model breaks the contract.
     */
    public IncidentSummarizer(LlmClient llm, PromptRegistry registry, IncidentSummaryPrompt prompt, boolean sendSchema,
                              Double temperature) {
        this.llm = Objects.requireNonNull(llm);
        this.prompt = Objects.requireNonNull(prompt);
        this.repairTemplate = registry.latest(REPAIR_PROMPT_ID);
        this.sendSchema = sendSchema;
        this.temperature = temperature;
    }

    public JsonContract<IncidentSummary> contract() {
        return contract;
    }

    public SummaryOutcome summarize(Alert alert, List<String> logLines) {
        ChatRequest first = prompt.request(alert, logLines);
        if (temperature != null) {
            first = new ChatRequest(first.systemPrompt(), first.userMessage(), temperature, first.maxTokens(),
                    first.promptRef(), first.outputSchema());
        }
        if (sendSchema) {
            first = first.withOutputSchema(contract.schema());
        }
        SummaryOutcome outcome = run(first);
        // Rule text only, never the reply itself: replies can quote customer data from the logs.
        LOG.log(System.Logger.Level.INFO, "incident.summary outcome={0} calls={1} prompt={2} schema={3} problems={4}",
                switch (outcome) {
                    case SummaryOutcome.Accepted a -> a.repaired() ? "repaired" : "accepted";
                    case SummaryOutcome.Rejected r -> "rejected";
                    case SummaryOutcome.Unavailable u -> "unavailable";
                },
                outcome.modelCalls(), first.promptRef(), sendSchema ? "yes" : "no",
                switch (outcome) {
                    case SummaryOutcome.Accepted a -> a.fixedViolations().size();
                    case SummaryOutcome.Rejected r -> r.firstViolations().size() + r.violations().size();
                    case SummaryOutcome.Unavailable u -> 0;
                });
        return outcome;
    }

    private SummaryOutcome run(ChatRequest first) {
        ChatResponse reply;
        try {
            reply = llm.chat(first);
        } catch (LlmException e) {
            return new SummaryOutcome.Unavailable(e.getMessage(), 1);
        }
        Checked checked = check(reply, first.userMessage());
        if (checked.summary() != null) {
            return new SummaryOutcome.Accepted(checked.summary(), 1, List.of(), checked.notes());
        }

        ChatRequest repair = repairRequest(first, reply.content(), checked.violations());
        ChatResponse second;
        try {
            second = llm.chat(repair);
        } catch (LlmException e) {
            return new SummaryOutcome.Unavailable(e.getMessage(), 2);
        }
        Checked rechecked = check(second, first.userMessage());
        if (rechecked.summary() != null) {
            return new SummaryOutcome.Accepted(rechecked.summary(), 2, checked.violations(), rechecked.notes());
        }
        return new SummaryOutcome.Rejected(checked.violations(), rechecked.violations(), second.content(), 2);
    }

    private record Checked(IncidentSummary summary, List<String> violations, List<String> notes) {
    }

    /** Shape first (the JSON contract), then meaning (checks against what was actually sent). */
    private Checked check(ChatResponse reply, String userMessage) {
        if (reply.truncated()) {
            return new Checked(null, List.of("the reply was cut off at the output token limit; keep the summary and lists short"),
                    List.of());
        }
        return switch (contract.parse(reply.content())) {
            case ParseResult.Invalid<IncidentSummary> invalid -> new Checked(null, invalid.violations(), List.of());
            case ParseResult.Valid<IncidentSummary> valid -> {
                List<String> violations = checks.check(valid.value(), userMessage);
                yield violations.isEmpty()
                        ? new Checked(valid.value(), List.of(), valid.notes())
                        : new Checked(null, violations, valid.notes());
            }
        };
    }

    /**
     * The repair keeps Article 6's split: instructions (the original contract and what was wrong) go in
     * the system prompt, from a versioned prompt; data (the alert, logs and the rejected reply) goes in
     * the user message.
     */
    ChatRequest repairRequest(ChatRequest first, String previousReply, List<String> violations) {
        String rendered = repairTemplate.render(Map.of(
                "original_instructions", first.systemPrompt(),
                "violations", violations.stream().map(v -> "- " + v).collect(Collectors.joining("\n"))))
                .text();
        String previous = previousReply == null ? "" : previousReply;
        if (previous.length() > MAX_PREVIOUS_REPLY_CHARS) {
            previous = previous.substring(0, MAX_PREVIOUS_REPLY_CHARS) + "\n[cut: the reply was longer]";
        }
        String user = first.userMessage() + "\n\nPREVIOUS REPLY:\n" + previous;
        return new ChatRequest(rendered, user, first.temperature(), first.maxTokens(),
                repairTemplate.ref().toString(), first.outputSchema());
    }
}
