package io.github.javatoailab.incidentpilot.context;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Capacity planning on real-looking data: how far off is the 4-characters-per-token rule for
 * prose versus logs, and what does an incident's context look like once it's budgeted?
 *
 * <p>Run with: {@code mvn -q -pl context-budget -am compile exec:java}
 */
public final class TokenReport {

    public static void main(String[] args) {
        String runbook = resource("samples/runbook.md");
        String logs = resource("samples/payment-service-incident.log");

        TokenCounter heuristic = new HeuristicTokenCounter();
        TokenCounter bpe = new JTokkitTokenCounter();

        System.out.println("Characters per token, measured with a real BPE tokenizer (cl100k_base):");
        row("Runbook prose", runbook, heuristic, bpe);
        row("Incident logs", logs, heuristic, bpe);

        List<String> lines = logs.lines().toList();
        Alert alert = new Alert("payment-service", "p99 latency above 2s for 5 minutes",
                "POST /v2/payments p99 latency 2.8s, error rate 4.1%", Instant.parse("2026-11-03T02:13:00Z"));
        String system = "You are IncidentPilot, an incident triage assistant. Summarize the incident from the alert and logs.";

        System.out.println();
        System.out.printf(Locale.ROOT, "The whole log as one prompt: %,d lines, ~%,d tokens, against an 8,192-token window.%n",
                lines.size(), bpe.count(logs));
        System.out.println();
        for (ContextBudget budget : List.of(new ContextBudget(4096, 1024, 0.10), ContextBudget.defaults(),
                new ContextBudget(32768, 1024, 0.10))) {
            IncidentContext ctx = new IncidentContextBuilder(bpe, budget).build(system, alert, lines);
            System.out.printf(Locale.ROOT,
                    "window %,6d | input budget %,6d | prompt %,6d tokens | %2d messages kept, %2d dropped, %,d repeats collapsed%n",
                    budget.contextWindow(), budget.inputBudget(), ctx.estimatedInputTokens(),
                    ctx.linesIncluded(), ctx.linesDropped(), ctx.repeatsCollapsed());
        }

        IncidentContext tight = new IncidentContextBuilder(bpe, new ContextBudget(4096, 1024, 0.10)).build(system, alert, lines);
        System.out.println();
        System.out.println("What the 4K prompt contains:");
        tight.userMessage().lines().forEach(l -> System.out.println("  " + (l.length() > 170 ? l.substring(0, 170) + " ..." : l)));
    }

    private static void row(String label, String text, TokenCounter heuristic, TokenCounter bpe) {
        int real = bpe.count(text);
        int guess = heuristic.count(text);
        System.out.printf(Locale.ROOT, "  %-14s %,8d chars | real %,7d tokens (%.2f chars/token) | 4-char rule guesses %,7d (%+.0f%%)%n",
                label, text.length(), real, (double) text.length() / real, guess, 100.0 * (guess - real) / real);
    }

    private static String resource(String path) {
        try (InputStream in = TokenReport.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
