package io.github.javatoailab.incidentpilot.prompt;

import io.github.javatoailab.incidentpilot.context.Alert;
import io.github.javatoailab.incidentpilot.context.ContextBudget;
import io.github.javatoailab.incidentpilot.context.IncidentContext;
import io.github.javatoailab.incidentpilot.context.IncidentContextBuilder;
import io.github.javatoailab.incidentpilot.context.TokenCounter;
import io.github.javatoailab.incidentpilot.llm.ChatRequest;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the model request for an incident summary from a versioned prompt.
 *
 * <p>The split is deliberate: the instructions and the output contract live in the system prompt,
 * which comes from a reviewed, versioned file. The incident data (alert and logs) goes in the user
 * message, assembled by the context-budget module from Article 5. Instructions never get mixed into
 * the data, and the data never gets mixed into the instructions.
 */
public final class IncidentSummaryPrompt {

    public static final String PROMPT_ID = "incident-summary";

    /** The team's severity scale. In a real deployment this comes from the incident policy, not code. */
    public static final String DEFAULT_SEVERITY_DEFINITIONS = String.join("\n",
            "- SEV1: customer-facing outage or data loss; page everyone now.",
            "- SEV2: a core flow (such as payments) is degraded for many customers.",
            "- SEV3: partial degradation with a workaround, or an internal system is down.",
            "- SEV4: no customer impact yet; needs attention during working hours.");

    public static final int DEFAULT_MAX_SUMMARY_WORDS = 80;

    private final PromptTemplate template;
    private final TokenCounter counter;
    private final ContextBudget budget;
    private final Map<String, Object> values;

    /**
     * @param selector {@code "incident-summary"} for the latest version, or {@code "incident-summary@1"} to pin one
     */
    public IncidentSummaryPrompt(PromptRegistry registry, String selector, TokenCounter counter, ContextBudget budget) {
        this.template = registry.resolve(selector);
        if (!template.id().equals(PROMPT_ID)) {
            throw new PromptContractException(selector + " is not an " + PROMPT_ID + " prompt");
        }
        this.counter = Objects.requireNonNull(counter);
        this.budget = Objects.requireNonNull(budget);
        this.values = valuesFor(template);
    }

    public PromptTemplate template() {
        return template;
    }

    /** The system prompt this version produces with the default settings. */
    public RenderedPrompt systemPrompt() {
        return template.render(values);
    }

    public IncidentContext context(Alert alert, List<String> logLines) {
        return new IncidentContextBuilder(counter, budget).build(systemPrompt().text(), alert, logLines);
    }

    public ChatRequest request(Alert alert, List<String> logLines) {
        return context(alert, logLines).toChatRequest(template.ref().toString());
    }

    /** Supplies exactly the variables this version declares: v1 takes one, v2 takes two. */
    private static Map<String, Object> valuesFor(PromptTemplate template) {
        Map<String, Object> all = Map.of(
                "max_summary_words", DEFAULT_MAX_SUMMARY_WORDS,
                "severity_definitions", DEFAULT_SEVERITY_DEFINITIONS);
        Map<String, Object> chosen = new java.util.LinkedHashMap<>();
        for (String name : template.variables()) {
            Object value = all.get(name);
            if (value == null) {
                throw new PromptContractException(template.ref() + " declares '" + name
                        + "', which IncidentSummaryPrompt does not know how to supply");
            }
            chosen.put(name, value);
        }
        return chosen;
    }
}
