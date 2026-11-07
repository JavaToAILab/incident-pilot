package io.github.javatoailab.incidentpilot.prompt;

import io.github.javatoailab.incidentpilot.context.Alert;
import io.github.javatoailab.incidentpilot.context.ContextBudget;
import io.github.javatoailab.incidentpilot.context.JTokkitTokenCounter;
import io.github.javatoailab.incidentpilot.llm.ChatRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IncidentSummaryPromptTest {

    private static final Alert ALERT = new Alert("payment-service", "p99 latency above 2s",
            "POST /v2/payments p99 2.8s", Instant.parse("2026-11-03T02:13:00Z"));
    private static final List<String> LOGS = List.of(
            "2026-11-03T02:12:58.000Z WARN  HikariPool-1 - Connection is not available",
            "2026-11-03T02:12:59.000Z ERROR PaymentService - could not persist ledger entry");

    private final PromptRegistry registry = PromptRegistry.load();
    private final JTokkitTokenCounter counter = new JTokkitTokenCounter();

    @Test
    void theRequestCarriesTheRefOfTheVersionThatBuiltIt() {
        ChatRequest latest = new IncidentSummaryPrompt(registry, "incident-summary", counter, ContextBudget.defaults())
                .request(ALERT, LOGS);
        ChatRequest pinned = new IncidentSummaryPrompt(registry, "incident-summary@1", counter, ContextBudget.defaults())
                .request(ALERT, LOGS);

        assertTrue(latest.promptRef().startsWith("incident-summary@v2#"), latest.promptRef());
        assertTrue(pinned.promptRef().startsWith("incident-summary@v1#"), pinned.promptRef());
        assertNotEquals(latest.systemPrompt(), pinned.systemPrompt());
    }

    @Test
    void instructionsStayInTheSystemPromptAndDataInTheUserMessage() {
        ChatRequest request = new IncidentSummaryPrompt(registry, "incident-summary", counter, ContextBudget.defaults())
                .request(ALERT, LOGS);

        assertTrue(request.systemPrompt().contains("Reply with a single JSON object"));
        assertFalse(request.systemPrompt().contains("HikariPool"), "no incident data in the instructions");
        assertTrue(request.userMessage().contains("HikariPool"));
        assertFalse(request.userMessage().contains("Reply with"), "no instructions in the data");
    }

    @Test
    void theSystemPromptMatchesTheReviewedGoldenCopy() throws IOException {
        // If this fails after a deliberate change, the change belongs in a new version, and this
        // golden file is regenerated from it and reviewed in the same pull request.
        String rendered = new IncidentSummaryPrompt(registry, "incident-summary@2", counter, ContextBudget.defaults())
                .systemPrompt().text();
        assertEquals(golden("golden/incident-summary-v2.txt"), rendered);
    }

    @Test
    void theV2PromptStillFitsTheBudgetWithRoomForLogs() {
        ContextBudget tight = new ContextBudget(4096, 1024, 0.10);
        int systemTokens = counter.count(new IncidentSummaryPrompt(registry, "incident-summary", counter, tight)
                .systemPrompt().text());

        assertTrue(systemTokens < tight.inputBudget() / 4,
                "the instructions should take under a quarter of a 4K budget, took " + systemTokens);
    }

    private static String golden(String path) throws IOException {
        try (InputStream in = IncidentSummaryPromptTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "missing " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").strip();
        }
    }
}
