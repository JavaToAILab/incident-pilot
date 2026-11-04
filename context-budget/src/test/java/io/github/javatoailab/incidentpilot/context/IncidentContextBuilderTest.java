package io.github.javatoailab.incidentpilot.context;

import io.github.javatoailab.incidentpilot.llm.ChatRequest;
import io.github.javatoailab.incidentpilot.llm.ChatResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IncidentContextBuilderTest {

    private static final Alert ALERT = new Alert("payment-service", "p99 latency above 2s",
            "POST /v2/payments p99 2.8s", Instant.parse("2026-11-03T02:13:00Z"));
    private static final String SYSTEM = "You are IncidentPilot. Summarize the incident.";
    private final TokenCounter counter = new JTokkitTokenCounter();

    /** Letters only, so each line is a genuinely different message, not a masked ID. */
    private static String word(int n) {
        StringBuilder sb = new StringBuilder();
        do {
            sb.append((char) ('a' + n % 26));
            n /= 26;
        } while (n > 0);
        return sb.append("ville").toString();
    }

    private static List<String> noisyLog(int infoLines) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < infoLines; i++) {
            lines.add("2026-11-03T02:0" + (i % 10) + ":00.000Z INFO  cache warmed for region " + word(i)
                    + " and tenant " + word(i * 7 + 3));
        }
        lines.add("2026-11-03T02:12:58.000Z ERROR PaymentService - could not persist ledger entry");
        lines.add("\tat com.acme.payments.repository.LedgerRepository.insert(LedgerRepository.java:57)");
        return lines;
    }

    @Test
    void fitsTheBudgetAndKeepsErrorsOverNoise() {
        ContextBudget budget = new ContextBudget(1024, 256, 0.10);
        IncidentContext ctx = new IncidentContextBuilder(counter, budget).build(SYSTEM, ALERT, noisyLog(500));

        assertTrue(ctx.estimatedInputTokens() <= budget.inputBudget(), "prompt must fit the input budget");
        assertTrue(ctx.userMessage().contains("could not persist ledger entry"), "the error must survive");
        assertTrue(ctx.userMessage().contains("LedgerRepository.insert"), "its stack frame travels with it");
        assertTrue(ctx.droppedAnything(), "500 info lines cannot all fit in 1K tokens");
    }

    @Test
    void alwaysIncludesTheAlert() {
        IncidentContext ctx = new IncidentContextBuilder(counter, ContextBudget.defaults()).build(SYSTEM, ALERT, List.of());

        assertTrue(ctx.userMessage().contains("payment-service"));
        assertTrue(ctx.userMessage().contains("p99 latency above 2s"));
        assertEquals(0, ctx.linesIncluded());
    }

    @Test
    void refusesWhenTheFixedPartCannotFit() {
        String hugeSystemPrompt = "Follow these rules carefully. ".repeat(400);
        ContextBudget tiny = new ContextBudget(512, 128, 0.0);

        ContextBudgetExceededException e = assertThrows(ContextBudgetExceededException.class,
                () -> new IncidentContextBuilder(counter, tiny).build(hugeSystemPrompt, ALERT, List.of()));
        assertTrue(e.requiredTokens() > e.availableTokens());
    }

    @Test
    void collapsesRepeatedMessagesAcrossTimestamps() {
        List<String> lines = new ArrayList<>();
        for (int s = 0; s < 40; s++) {
            lines.add(String.format("2026-11-03T02:10:%02d.000Z WARN  HikariPool-1 - Connection is not available", s));
        }
        IncidentContext ctx = new IncidentContextBuilder(counter, ContextBudget.defaults()).build(SYSTEM, ALERT, lines);

        assertEquals(1, ctx.linesIncluded());
        assertEquals(39, ctx.repeatsCollapsed());
        assertTrue(ctx.userMessage().contains("(repeated 40x since 2026-11-03T02:10:00.000Z)"), ctx.userMessage());
    }

    @Test
    void collapsesMessagesThatDifferOnlyByIdsAndNumbers() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            lines.add("2026-11-03T02:11:" + String.format("%02d", i) + ".000Z ERROR [payment-7c9f8d6b5-m8zt4] trace_id="
                    + Long.toHexString(0x9a3f00c0ffeeL + i) + " Payment " + java.util.UUID.randomUUID() + " failed after " + (3000 + i) + "ms");
        }
        IncidentContext ctx = new IncidentContextBuilder(counter, ContextBudget.defaults()).build(SYSTEM, ALERT, lines);

        assertEquals(1, ctx.linesIncluded(), ctx.userMessage());
        assertTrue(ctx.userMessage().contains("(repeated 30x since 2026-11-03T02:11:00.000Z)"), ctx.userMessage());
        assertTrue(ctx.userMessage().contains("2026-11-03T02:11:29.000Z"), "the latest example is the one shown");
    }

    @Test
    void underATightBudgetTheCauseSurvivesNotJustTheSymptom() {
        List<String> lines = new ArrayList<>(noisyLog(300));
        for (int i = 0; i < 50; i++) {
            lines.add("2026-11-03T02:12:" + String.format("%02d", i) + ".000Z WARN  HikariPool-1 - Connection is not available, waiting=" + (10 + i));
            lines.add("2026-11-03T02:12:" + String.format("%02d", i) + ".500Z ERROR PaymentService - Payment " + java.util.UUID.randomUUID() + " failed");
        }
        IncidentContext ctx = new IncidentContextBuilder(counter, new ContextBudget(1024, 256, 0.10)).build(SYSTEM, ALERT, lines);

        assertTrue(ctx.userMessage().contains("Connection is not available"), "the pool warning explains the errors");
        assertTrue(ctx.userMessage().contains("PaymentService - Payment"), ctx.userMessage());
    }

    @Test
    void keepsChosenLinesInTimeOrder() {
        List<String> lines = List.of(
                "2026-11-03T02:00:01Z INFO  first",
                "2026-11-03T02:00:02Z ERROR second",
                "2026-11-03T02:00:03Z WARN  third");
        String msg = new IncidentContextBuilder(counter, ContextBudget.defaults()).build(SYSTEM, ALERT, lines).userMessage();

        assertTrue(msg.indexOf("first") < msg.indexOf("second") && msg.indexOf("second") < msg.indexOf("third"), msg);
    }

    @Test
    void truncatesOverlongLines() {
        String giant = "2026-11-03T02:00:01Z ERROR payload=" + "x".repeat(5000);
        String msg = new IncidentContextBuilder(counter, ContextBudget.defaults(), 200)
                .build(SYSTEM, ALERT, List.of(giant)).userMessage();

        assertTrue(msg.contains("...[truncated]"));
        assertFalse(msg.contains("x".repeat(300)));
    }

    @Test
    void tellsTheModelWhatWasLeftOut() {
        IncidentContext ctx = new IncidentContextBuilder(counter, new ContextBudget(1024, 256, 0.10))
                .build(SYSTEM, ALERT, noisyLog(500));

        assertTrue(ctx.userMessage().contains(ctx.linesDropped() + " lower-priority messages omitted"), ctx.userMessage());
    }

    @Test
    void capsTheReplyAtTheOutputReservation() {
        ContextBudget budget = new ContextBudget(8192, 700, 0.10);
        ChatRequest request = new IncidentContextBuilder(counter, budget).build(SYSTEM, ALERT, List.of()).toChatRequest();

        assertEquals(700, request.maxTokens());
        assertEquals(SYSTEM, request.systemPrompt());
    }

    @Test
    void budgetReservesOutputAndMargin() {
        assertEquals(6451, ContextBudget.defaults().inputBudget()); // (8192 - 1024) * 0.9, floored
        assertThrows(IllegalArgumentException.class, () -> new ContextBudget(1000, 1000, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new ContextBudget(1000, 100, 0.9));
    }

    @Test
    void heuristicUnderCountsLogsComparedToARealTokenizer() {
        String logs = String.join("\n", noisyLog(200));
        int heuristic = new HeuristicTokenCounter().count(logs);
        int real = counter.count(logs);

        assertTrue(real > heuristic, "logs tokenize worse than prose: real=" + real + " heuristic=" + heuristic);
    }

    @Test
    void calibratorLearnsFromReportedUsage() {
        TokenCalibrator calibrator = new TokenCalibrator(4.0, 0.5);
        IncidentContext ctx = new IncidentContextBuilder(counter, ContextBudget.defaults()).build(SYSTEM, ALERT, noisyLog(50));
        int chars = ctx.systemPrompt().length() + ctx.userMessage().length();
        ChatResponse response = new ChatResponse("ok", "llama3.2", chars / 3, 10, "stop", Duration.ofMillis(5), 1);

        calibrator.observe(ctx, response);

        assertEquals(1, calibrator.observations());
        assertEquals(3.0, calibrator.charsPerToken(), 0.05);
        calibrator.observe(0, 0); // no usage reported: ignored
        assertEquals(1, calibrator.observations());
    }

    @Test
    void pricesTheWorstCase() {
        ModelPricing pricing = new ModelPricing(1.00, 4.00); // illustrative, not a real price list
        assertEquals(0.0010 + 0.0040, pricing.worstCaseCost(1_000, 1_000), 1e-9);
        assertEquals(0.0, ModelPricing.LOCAL.worstCaseCost(5_000, 1_000));
    }
}
