package io.github.javatoailab.incidentpilot.prompt;

import io.github.javatoailab.incidentpilot.context.Alert;
import io.github.javatoailab.incidentpilot.context.ContextBudget;
import io.github.javatoailab.incidentpilot.context.IncidentContext;
import io.github.javatoailab.incidentpilot.context.JTokkitTokenCounter;
import io.github.javatoailab.incidentpilot.context.TokenCounter;
import io.github.javatoailab.incidentpilot.llm.ChatRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * What each released version of the incident-summary prompt costs and carries, measured with a real
 * tokenizer against the sample incident from Article 5.
 *
 * <p>Run with {@code mvn -q -pl prompt-contracts exec:java}. Pass {@code --lock} to print lock-file
 * lines for the prompt files in {@code src/main/resources/prompts} after adding a new version.
 */
public final class PromptReport {

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--lock")) {
            Path dir = Path.of(args.length > 1 ? args[1]
                    : System.getProperty("prompts.dir", "src/main/resources/prompts"));
            PromptRegistry.lockLines(dir).forEach(System.out::println);
            return;
        }

        PromptRegistry registry = PromptRegistry.load();
        TokenCounter counter = new JTokkitTokenCounter();
        ContextBudget budget = ContextBudget.defaults();
        Alert alert = new Alert("payment-service", "p99 latency above 2s for 5 minutes",
                "POST /v2/payments p99 latency 2.8s, error rate 4.1%", Instant.parse("2026-11-03T02:13:00Z"));
        List<String> logs = resource("samples/payment-service-incident.log").lines().toList();

        System.out.println("Released versions of 'incident-summary' (tokens counted with cl100k_base):");
        System.out.println();
        System.out.println("ref                            inputs  system prompt  whole request  logs kept");
        int previous = -1;
        for (PromptTemplate template : registry.versions(IncidentSummaryPrompt.PROMPT_ID).values()) {
            IncidentSummaryPrompt prompt = new IncidentSummaryPrompt(registry,
                    template.id() + "@" + template.version(), counter, budget);
            int systemTokens = counter.count(prompt.systemPrompt().text());
            IncidentContext ctx = prompt.context(alert, logs);
            System.out.printf(Locale.ROOT, "%-30s %6d  %,13d  %,13d  %9d%n",
                    template.ref(), template.variables().size(), systemTokens, ctx.estimatedInputTokens(),
                    ctx.linesIncluded());
            if (previous >= 0) {
                System.out.printf(Locale.ROOT, "  -> %+d tokens on every call; %+,d input tokens per 10,000 calls%n",
                        systemTokens - previous, (systemTokens - previous) * 10_000L);
            }
            previous = systemTokens;
        }

        IncidentSummaryPrompt latest = new IncidentSummaryPrompt(registry, IncidentSummaryPrompt.PROMPT_ID, counter, budget);
        ChatRequest request = latest.request(alert, logs);
        System.out.println();
        System.out.println("The request carries its prompt ref, so the log line for this call would read:");
        System.out.println("  llm.call model=llama3.2 prompt=" + request.promptRef() + " attempts=1 ...");
        System.out.println();
        System.out.println("Rendered system prompt for " + request.promptRef() + ":");
        System.out.println("----");
        System.out.println(request.systemPrompt());
        System.out.println("----");
    }

    private static String resource(String path) {
        try (InputStream in = PromptReport.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
