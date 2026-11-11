package io.github.javatoailab.incidentpilot.structured;

import io.github.javatoailab.incidentpilot.context.Alert;
import io.github.javatoailab.incidentpilot.context.ContextBudget;
import io.github.javatoailab.incidentpilot.context.JTokkitTokenCounter;
import io.github.javatoailab.incidentpilot.llm.ChatRequest;
import io.github.javatoailab.incidentpilot.llm.ChatResponse;
import io.github.javatoailab.incidentpilot.llm.LlmClient;
import io.github.javatoailab.incidentpilot.llm.LlmException;
import io.github.javatoailab.incidentpilot.prompt.IncidentSummaryPrompt;
import io.github.javatoailab.incidentpilot.prompt.PromptRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class IncidentSummarizerTest {

    static final Alert ALERT = new Alert("payment-service", "p99 latency above 2s",
            "POST /v2/payments p99 2.8s", Instant.parse("2026-11-03T02:13:00Z"));
    static final String LEDGER = "2026-11-03T02:12:59.000Z ERROR PaymentService - could not persist ledger entry";
    static final List<String> LOGS = List.of(
            "2026-11-03T02:12:58.000Z WARN  HikariPool-1 - Connection is not available", LEDGER);

    static final String GOOD = """
            {"severity":"SEV2","suspected_component":"payments-db pool","summary":"Payments cannot persist; the pool is exhausted.",
             "evidence":["%s"],"confidence":"medium","unknowns":["Was there a deploy?"]}""".formatted(LEDGER);

    private final PromptRegistry registry = PromptRegistry.load();
    private final IncidentSummaryPrompt prompt = new IncidentSummaryPrompt(registry, "incident-summary",
            new JTokkitTokenCounter(), ContextBudget.defaults());

    /** A fake model: canned replies in order, and a record of every request it was sent. */
    static final class FakeModel implements LlmClient {
        final Deque<Object> replies = new ArrayDeque<>();
        final List<ChatRequest> requests = new ArrayList<>();

        FakeModel reply(String content) {
            replies.add(new ChatResponse(content, "fake", 100, 50, "stop", Duration.ofMillis(5), 1));
            return this;
        }

        FakeModel cutOff(String content) {
            replies.add(new ChatResponse(content, "fake", 100, 512, "length", Duration.ofMillis(5), 1));
            return this;
        }

        FakeModel fail(LlmException e) {
            replies.add(e);
            return this;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            requests.add(request);
            Object next = replies.poll();
            if (next instanceof LlmException e) {
                throw e;
            }
            return (ChatResponse) Objects.requireNonNull(next, "no reply scripted");
        }
    }

    private SummaryOutcome run(FakeModel model, boolean schema) {
        return new IncidentSummarizer(model, registry, prompt, schema).summarize(ALERT, LOGS);
    }

    @Test
    void aValidFirstReplyTakesOneCall() {
        FakeModel model = new FakeModel().reply(GOOD);

        SummaryOutcome.Accepted a = assertInstanceOf(SummaryOutcome.Accepted.class, run(model, false));

        assertEquals(Severity.SEV2, a.summary().severity());
        assertEquals(1, a.modelCalls());
        assertFalse(a.repaired());
        assertNull(model.requests.getFirst().outputSchema());
    }

    @Test
    void aBrokenReplyIsRepairedOnceWithTheReasonsInTheInstructions() {
        FakeModel model = new FakeModel().reply("Here is the summary: " + GOOD).reply(GOOD);

        SummaryOutcome.Accepted a = assertInstanceOf(SummaryOutcome.Accepted.class, run(model, false));

        assertTrue(a.repaired());
        assertEquals(2, a.modelCalls());
        ChatRequest repair = model.requests.get(1);
        assertTrue(repair.promptRef().startsWith("incident-summary-repair@v1#"), repair.promptRef());
        // Instructions, including what was wrong, go in the system prompt...
        assertTrue(repair.systemPrompt().contains("must be a single JSON object"), repair.systemPrompt());
        assertTrue(repair.systemPrompt().contains("Reply with a single JSON object and nothing else"),
                "the original contract is repeated");
        // ...and the data, including the rejected reply, goes in the user message.
        assertTrue(repair.userMessage().contains(LEDGER));
        assertTrue(repair.userMessage().contains("PREVIOUS REPLY:\nHere is the summary:"));
    }

    @Test
    void twoBadRepliesAreRejectedAfterExactlyTwoCalls() {
        FakeModel model = new FakeModel().reply("not json").reply("still not json").reply(GOOD);

        SummaryOutcome.Rejected r = assertInstanceOf(SummaryOutcome.Rejected.class, run(model, false));

        assertEquals(2, r.modelCalls());
        assertEquals(2, model.requests.size(), "one repair, never a loop");
        assertEquals("still not json", r.lastReply());
        assertFalse(r.firstViolations().isEmpty());
    }

    @Test
    void validJsonWithInventedEvidenceIsNotAccepted() {
        String invented = GOOD.replace(LEDGER, "ERROR PaymentService - database connection refused by host");
        FakeModel model = new FakeModel().reply(invented).reply(GOOD);

        SummaryOutcome.Accepted a = assertInstanceOf(SummaryOutcome.Accepted.class, run(model, true));

        assertEquals(List.of("\"evidence\"[0] is not a line from the LOGS; copy log lines exactly"), a.fixedViolations());
    }

    @Test
    void aReplyCutOffAtTheTokenLimitIsNotParsedAsIfItWereComplete() {
        FakeModel model = new FakeModel().cutOff(GOOD).reply(GOOD);

        SummaryOutcome.Accepted a = assertInstanceOf(SummaryOutcome.Accepted.class, run(model, false));

        assertTrue(a.fixedViolations().getFirst().contains("cut off at the output token limit"));
    }

    @Test
    void anUnreachableModelIsAnOutcomeNotAnException() {
        FakeModel model = new FakeModel().fail(new LlmException("Timed out after PT30S", true, -1));

        SummaryOutcome.Unavailable u = assertInstanceOf(SummaryOutcome.Unavailable.class, run(model, false));

        assertEquals(1, u.modelCalls());
        assertTrue(u.reason().contains("Timed out"));
    }

    @Test
    void withSchemaModeBothRequestsCarryTheSchema() {
        FakeModel model = new FakeModel().reply("{}").reply(GOOD);
        run(model, true);

        String schema = new JsonContract<>(IncidentSummary.class).schema();
        assertEquals(schema, model.requests.get(0).outputSchema());
        assertEquals(schema, model.requests.get(1).outputSchema());
    }

    @Test
    void theRecordAndThePromptAskForTheSameFields() {
        // Three descriptions of one contract: the prompt text, the record, and the schema generated
        // from the record. The schema can't drift from the record. This test stops the prompt drifting.
        String instructions = prompt.systemPrompt().text();
        Matcher m = Pattern.compile("^\\s*\"([a-z_]+)\":", Pattern.MULTILINE).matcher(instructions);
        List<String> promptFields = new ArrayList<>();
        while (m.find()) {
            promptFields.add(m.group(1));
        }
        assertEquals(new JsonContract<>(IncidentSummary.class).fieldNames(), promptFields);
    }

    @Test
    void aLongRejectedReplyIsCutBeforeItIsQuotedBack() {
        IncidentSummarizer summarizer = new IncidentSummarizer(new FakeModel(), registry, prompt, false);
        ChatRequest first = prompt.request(ALERT, LOGS);

        ChatRequest repair = summarizer.repairRequest(first, "x".repeat(10_000), List.of("the reply was empty"));

        assertTrue(repair.userMessage().length() < first.userMessage().length() + IncidentSummarizer.MAX_PREVIOUS_REPLY_CHARS + 100);
        assertTrue(repair.userMessage().endsWith("[cut: the reply was longer]"));
    }
}
