package io.github.javatoailab.incidentpilot.structured;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IncidentSummaryChecksTest {

    static final String SENT = """
            ALERT payment-service: p99 latency above 2s
            LOGS (2 of 2 distinct messages shown in time order; 0 left out)
            2026-11-03T02:06:00.512Z WARN  HikariPool-1 - Connection is not available, request timed out after 3000ms
            2026-11-03T02:06:11.951Z ERROR PaymentService - Payment 12b9 failed: could not persist ledger entry
            """;

    private final IncidentSummaryChecks checks = new IncidentSummaryChecks(20);

    private static IncidentSummary summary(String text, List<String> evidence, Confidence confidence) {
        return new IncidentSummary(Severity.SEV2, "payments-db pool", text, evidence, confidence, List.of());
    }

    @Test
    void acceptsEvidenceCopiedFromTheLogsEvenWithDifferentSpacing() {
        IncidentSummary s = summary("The pool is exhausted.",
                List.of("ERROR  PaymentService - Payment 12b9 failed:  could not persist ledger entry"), Confidence.HIGH);
        assertEquals(List.of(), checks.check(s, SENT));
    }

    @Test
    void rejectsEvidenceThatWasNeverInTheLogs() {
        // Valid JSON, valid schema, plausible text. Only a check against the input catches it.
        IncidentSummary s = summary("The database refused connections.",
                List.of("ERROR PaymentService - database connection refused"), Confidence.HIGH);
        assertEquals(List.of("\"evidence\"[0] is not a line from the LOGS; copy log lines exactly"), checks.check(s, SENT));
    }

    @Test
    void enforcesTheLimitsThePromptStates() {
        IncidentSummary s = summary("word ".repeat(21), List.of("WARN", "WARN", "WARN", "WARN", "WARN", "WARN"),
                Confidence.LOW);
        List<String> v = checks.check(s, SENT);
        assertTrue(v.contains("\"evidence\" has 6 lines; the limit is 5"), v.toString());
        assertTrue(v.contains("\"summary\" has 21 words; the limit is 20"), v.toString());
    }

    @Test
    void highConfidenceNeedsEvidence() {
        IncidentSummary s = summary("The pool is exhausted.", List.of(), Confidence.HIGH);
        assertEquals(List.of("\"confidence\" is \"high\" but no evidence was quoted"), checks.check(s, SENT));
        assertEquals(List.of(), checks.check(summary("Unclear.", List.of(), Confidence.LOW), SENT));
    }
}
