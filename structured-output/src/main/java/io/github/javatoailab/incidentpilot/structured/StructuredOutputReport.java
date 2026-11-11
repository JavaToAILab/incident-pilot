package io.github.javatoailab.incidentpilot.structured;

import io.github.javatoailab.incidentpilot.context.Alert;
import io.github.javatoailab.incidentpilot.context.ContextBudget;
import io.github.javatoailab.incidentpilot.context.JTokkitTokenCounter;
import io.github.javatoailab.incidentpilot.context.TokenCounter;
import io.github.javatoailab.incidentpilot.llm.ChatRequest;
import io.github.javatoailab.incidentpilot.llm.ChatResponse;
import io.github.javatoailab.incidentpilot.llm.LlmClient;
import io.github.javatoailab.incidentpilot.llm.LlmConfig;
import io.github.javatoailab.incidentpilot.llm.OllamaClient;
import io.github.javatoailab.incidentpilot.prompt.IncidentSummaryPrompt;
import io.github.javatoailab.incidentpilot.prompt.PromptRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Shows the structured-output pipeline on the sample incident from Article 5.
 *
 * <p>{@code mvn -q -pl structured-output exec:java} replays the ways model replies commonly break a
 * JSON contract, through the real parser, checks and repair step, using a scripted model, so it runs
 * anywhere. {@code -Dexec.args="--live 20"} sends the sample incident to a real model (Ollama, configured
 * with the usual {@code LLM_*} environment variables) that many times, with and without the schema,
 * and counts the outcomes.
 */
public final class StructuredOutputReport {

    static final Alert ALERT = new Alert("payment-service", "p99 latency above 2s for 5 minutes",
            "POST /v2/payments p99 latency 2.8s, error rate 4.1%", Instant.parse("2026-11-03T02:13:00Z"));

    public static void main(String[] args) {
        List<String> logs = resource("samples/payment-service-incident.log").lines().toList();
        PromptRegistry registry = PromptRegistry.load();
        TokenCounter counter = new JTokkitTokenCounter();
        IncidentSummaryPrompt prompt = new IncidentSummaryPrompt(registry, IncidentSummaryPrompt.PROMPT_ID, counter,
                ContextBudget.defaults());

        if (args.length > 0 && args[0].equals("--live")) {
            int runs = args.length > 1 ? Integer.parseInt(args[1]) : 10;
            double temperature = args.length > 2 ? Double.parseDouble(args[2]) : 0.7;
            live(registry, prompt, logs, runs, temperature);
            return;
        }
        replay(registry, prompt, counter, logs);
    }

    // ---------------------------------------------------------------- scripted replay

    private static void replay(PromptRegistry registry, IncidentSummaryPrompt prompt, TokenCounter counter, List<String> logs) {
        // The summarizer's one-line-per-incident log is useful in production and noise in this table.
        java.util.logging.Logger.getLogger(IncidentSummarizer.class.getName()).setLevel(java.util.logging.Level.WARNING);
        ChatRequest first = prompt.request(ALERT, logs);
        String evidence = first.userMessage().lines()
                .filter(l -> l.contains("could not persist ledger entry")).findFirst().orElseThrow();
        String good = json("SEV2", "payment-service database connection pool",
                "Payments are failing to persist because the HikariCP pool is exhausted (20 of 20 connections active).",
                evidence, "medium");

        record Case(String name, List<Reply> replies) {
        }
        List<Case> cases = List.of(
                new Case("clean JSON", List.of(ok(good))),
                new Case("JSON in a markdown fence", List.of(ok("```json\n" + good + "\n```"))),
                new Case("a sentence before the JSON", List.of(ok("Here is the incident summary:\n" + good), ok(good))),
                new Case("severity outside the scale", List.of(ok(good.replace("\"SEV2\"", "\"critical\"")), ok(good))),
                new Case("field renamed", List.of(ok(good.replace("\"suspected_component\"", "\"component\"")), ok(good))),
                new Case("evidence not in the logs", List.of(ok(good.replace(jsonString(evidence),
                        jsonString("ERROR PaymentService - database connection refused"))), ok(good))),
                new Case("cut off at the token limit", List.of(new Reply(good.substring(0, 120), "length"), ok(good))),
                new Case("wrong twice", List.of(ok("The payment service is degraded."), ok("Still not JSON, sorry."))));

        System.out.println("Each case is a first reply (and, if needed, the reply to the repair request),");
        System.out.println("run through the real JSON contract, input checks and single repair step.");
        System.out.println();
        System.out.printf(Locale.ROOT, "%-28s %-11s %5s  %s%n", "first reply", "outcome", "calls", "what the checks said");
        for (Case c : cases) {
            ScriptedModel model = new ScriptedModel(c.replies());
            SummaryOutcome outcome = new IncidentSummarizer(model, registry, prompt, false).summarize(ALERT, logs);
            String verdict = switch (outcome) {
                case SummaryOutcome.Accepted a when a.repaired() -> "repaired";
                case SummaryOutcome.Accepted a -> "accepted";
                case SummaryOutcome.Rejected r -> "rejected";
                case SummaryOutcome.Unavailable u -> "unavailable";
            };
            List<String> said = switch (outcome) {
                case SummaryOutcome.Accepted a -> a.repaired() ? a.fixedViolations() : a.notes();
                case SummaryOutcome.Rejected r -> r.violations();
                case SummaryOutcome.Unavailable u -> List.of(u.reason());
            };
            System.out.printf(Locale.ROOT, "%-28s %-11s %5d  %s%n", c.name(), verdict, outcome.modelCalls(),
                    said.isEmpty() ? "-" : said.getFirst());
            for (String more : said.subList(Math.min(1, said.size()), said.size())) {
                System.out.printf(Locale.ROOT, "%-28s %-11s %5s  %s%n", "", "", "", more);
            }
        }

        IncidentSummarizer summarizer = new IncidentSummarizer(new ScriptedModel(List.of()), registry, prompt, false);
        ChatRequest repair = summarizer.repairRequest(first, "Here is the incident summary:\n" + good,
                List.of("the reply must be a single JSON object and nothing else, but it starts with \"Here is the incident summary: {...\""));
        int firstTokens = counter.count(first.systemPrompt()) + counter.count(first.userMessage());
        int repairTokens = counter.count(repair.systemPrompt()) + counter.count(repair.userMessage());
        System.out.println();
        System.out.printf(Locale.ROOT, "First request:  %,d input tokens (%s)%n", firstTokens, first.promptRef());
        System.out.printf(Locale.ROOT, "Repair request: %,d input tokens (%s), %.1fx the first%n", repairTokens,
                repair.promptRef(), (double) repairTokens / firstTokens);
        System.out.println();
        System.out.println("JSON Schema generated from the IncidentSummary record:");
        System.out.println(summarizer.contract().schema());
    }

    private static String json(String severity, String component, String summary, String evidence, String confidence) {
        return "{\"severity\":\"" + severity + "\",\"suspected_component\":" + jsonString(component)
                + ",\"summary\":" + jsonString(summary) + ",\"evidence\":[" + jsonString(evidence) + "]"
                + ",\"confidence\":\"" + confidence + "\""
                + ",\"unknowns\":[\"Was there a deploy or a traffic spike before 02:06?\"]}";
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    record Reply(String content, String stopReason) {
    }

    private static Reply ok(String content) {
        return new Reply(content, "stop");
    }

    /** Answers with canned replies, in order. Stands in for the model so the report runs anywhere. */
    static final class ScriptedModel implements LlmClient {
        private final Deque<Reply> replies;

        ScriptedModel(List<Reply> replies) {
            this.replies = new ArrayDeque<>(replies);
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            Reply r = replies.poll();
            if (r == null) {
                throw new IllegalStateException("the scripted model ran out of replies");
            }
            return new ChatResponse(r.content(), "scripted", 0, 0, r.stopReason(), Duration.ZERO, 1);
        }
    }

    // ---------------------------------------------------------------- live run

    private static void live(PromptRegistry registry, IncidentSummaryPrompt prompt, List<String> logs, int runs,
                             double temperature) {
        LlmConfig config = LlmConfig.fromEnv();
        LlmClient model = new OllamaClient(config);
        System.out.printf(Locale.ROOT, "Model %s at %s, %d runs per mode, temperature %.1f%n%n",
                config.model(), config.baseUrl(), runs, temperature);
        for (boolean schema : new boolean[]{false, true}) {
            IncidentSummarizer summarizer = new IncidentSummarizer(model, registry, prompt, schema, temperature);
            Map<String, Integer> outcomes = new TreeMap<>();
            Map<String, Integer> problems = new TreeMap<>();
            Map<String, Integer> severities = new TreeMap<>();
            int calls = 0;
            long started = System.nanoTime();
            for (int i = 0; i < runs; i++) {
                SummaryOutcome outcome = summarizer.summarize(ALERT, logs);
                calls += outcome.modelCalls();
                List<String> found = new ArrayList<>();
                switch (outcome) {
                    case SummaryOutcome.Accepted a -> {
                        outcomes.merge(a.repaired() ? "2 accepted after repair" : "1 accepted first time", 1, Integer::sum);
                        severities.merge(a.summary().severity().name(), 1, Integer::sum);
                        found.addAll(a.fixedViolations());
                        found.addAll(a.notes());
                    }
                    case SummaryOutcome.Rejected r -> {
                        outcomes.merge("3 rejected", 1, Integer::sum);
                        found.addAll(r.firstViolations());
                        found.addAll(r.violations());
                    }
                    case SummaryOutcome.Unavailable u -> {
                        outcomes.merge("4 unavailable", 1, Integer::sum);
                        found.add("unavailable: " + u.reason());
                    }
                }
                found.forEach(p -> problems.merge(category(p), 1, Integer::sum));
                System.out.print(".");
            }
            System.out.println();
            System.out.printf(Locale.ROOT, "%s (%d model calls, %.1f s)%n", schema ? "With the JSON Schema" : "Prompt only",
                    calls, (System.nanoTime() - started) / 1e9);
            outcomes.forEach((k, v) -> System.out.printf(Locale.ROOT, "  %-26s %d%n", k.substring(2), v));
            System.out.println("  severity of accepted summaries: " + severities);
            System.out.println("  problems seen (first replies and repairs): " + (problems.isEmpty() ? "none" : problems));
            System.out.println();
        }
    }

    static String category(String problem) {
        if (problem.contains("markdown code fence")) return "fenced (tolerated)";
        if (problem.contains("single JSON object") || problem.contains("not valid JSON") || problem.contains("empty")) return "not a JSON object";
        if (problem.contains("cut off")) return "cut off";
        if (problem.contains("missing field")) return "missing field";
        if (problem.contains("unexpected field")) return "extra field";
        if (problem.contains("must be one of")) return "value outside the allowed set";
        if (problem.contains("must be")) return "wrong type";
        if (problem.contains("not a line from the LOGS")) return "evidence not in the logs";
        if (problem.contains("evidence\" has")) return "too much evidence";
        if (problem.contains("words")) return "summary too long";
        if (problem.contains("confidence")) return "high confidence, no evidence";
        if (problem.startsWith("unavailable")) return "model unavailable";
        return "other";
    }

    private static String resource(String path) {
        try (InputStream in = StructuredOutputReport.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
