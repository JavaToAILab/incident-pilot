package io.github.javatoailab.incidentpilot.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fits an alert and its logs into a {@link ContextBudget}, keeping the lines that matter most.
 *
 * <p>The rules, in order:
 * <ol>
 *   <li>The system prompt and the alert always go in. If they alone exceed the budget, fail loudly.</li>
 *   <li>Overlong lines are cut, so one giant stack frame or JSON blob can't eat the budget.</li>
 *   <li>Lines with the same message template (same text once IDs, numbers and timestamps are
 *       masked) collapse into their latest example plus a count.</li>
 *   <li>Messages are admitted by priority: errors (with their stack traces), then warnings, then
 *       info, then the rest; within a priority, the most recently seen first.</li>
 *   <li>The chosen messages go back into time order, so the model reads a timeline.</li>
 *   <li>The prompt says how much was left out, so the model knows it is seeing a sample.</li>
 * </ol>
 */
public final class IncidentContextBuilder {

    /** No single log line may cost more than this many characters. */
    public static final int DEFAULT_MAX_LINE_CHARS = 400;

    private final TokenCounter counter;
    private final ContextBudget budget;
    private final int maxLineChars;

    public IncidentContextBuilder(TokenCounter counter, ContextBudget budget, int maxLineChars) {
        this.counter = Objects.requireNonNull(counter);
        this.budget = Objects.requireNonNull(budget);
        if (maxLineChars < 40) {
            throw new IllegalArgumentException("maxLineChars must be at least 40");
        }
        this.maxLineChars = maxLineChars;
    }

    public IncidentContextBuilder(TokenCounter counter, ContextBudget budget) {
        this(counter, budget, DEFAULT_MAX_LINE_CHARS);
    }

    /** One message template: its most recent example, how often it occurred, and when it started. */
    private static final class Group {
        LogLine latest;
        String firstSeen;
        int count;
    }

    public IncidentContext build(String systemPrompt, Alert alert, List<String> rawLogLines) {
        Objects.requireNonNull(alert, "alert");
        String system = systemPrompt == null ? "" : systemPrompt;
        String alertBlock = alert.render();
        int available = budget.inputBudget();

        // Rule 1: the fixed part must fit, or we refuse to build a prompt at all.
        int fixed = counter.count(system) + counter.count(alertBlock) + counter.count(header(0, 0, 0, 0));
        if (fixed > available) {
            throw new ContextBudgetExceededException(fixed, available);
        }

        // Rules 2 and 3: parse, truncate, and group by template.
        Map<String, Group> groups = new LinkedHashMap<>();
        int previousPriority = LogLine.OTHER;
        int rawCount = 0;
        List<String> lines = rawLogLines == null ? List.of() : rawLogLines;
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            rawCount++;
            LogLine line = LogLine.parse(i, raw, previousPriority, maxLineChars);
            previousPriority = line.priority();
            Group g = groups.computeIfAbsent(line.priority() + "|" + line.template(), k -> new Group());
            if (g.count == 0) {
                g.firstSeen = line.timestamp();
            }
            g.latest = line;
            g.count++;
        }
        int repeatsCollapsed = rawCount - groups.size();

        // Rule 4: admit by priority, most recently seen first, until the budget is spent.
        List<Group> candidates = new ArrayList<>(groups.values());
        candidates.sort(Comparator.comparingInt((Group g) -> g.latest.priority())
                .thenComparing(Comparator.comparingInt((Group g) -> g.latest.index()).reversed()));

        int remaining = available - fixed;
        List<Group> chosen = new ArrayList<>();
        for (Group g : candidates) {
            int cost = counter.count(render(g)) + 1; // +1 for the newline
            if (cost <= remaining) {
                chosen.add(g);
                remaining -= cost;
            }
        }

        // Rule 5: back into time order. Then render and re-check the real total, dropping
        // the least important message until it fits (token boundaries rarely add up exactly).
        chosen.sort(Comparator.comparingInt((Group g) -> g.latest.index()));
        String user = render(alertBlock, chosen, groups.size(), repeatsCollapsed);
        int total = counter.count(system) + counter.count(user);
        while (total > available && !chosen.isEmpty()) {
            Group weakest = chosen.stream()
                    .max(Comparator.comparingInt((Group g) -> g.latest.priority())
                            .thenComparing(Comparator.comparingInt((Group g) -> g.latest.index()).reversed()))
                    .orElseThrow();
            chosen.remove(weakest);
            user = render(alertBlock, chosen, groups.size(), repeatsCollapsed);
            total = counter.count(system) + counter.count(user);
        }

        return new IncidentContext(system, user, budget, total,
                chosen.size(), groups.size() - chosen.size(), repeatsCollapsed);
    }

    private static String render(String alertBlock, List<Group> chosen, int uniqueCount, int repeatsCollapsed) {
        StringBuilder sb = new StringBuilder(alertBlock).append('\n');
        sb.append(header(chosen.size(), uniqueCount, uniqueCount - chosen.size(), repeatsCollapsed)).append('\n');
        for (Group g : chosen) {
            sb.append(render(g)).append('\n');
        }
        return sb.toString();
    }

    private static String render(Group g) {
        if (g.count == 1) {
            return g.latest.text();
        }
        return g.latest.text() + " (repeated " + g.count + "x" + (g.firstSeen == null ? "" : " since " + g.firstSeen) + ")";
    }

    // Rule 6: tell the model it is looking at a sample, not the whole log.
    private static String header(int shown, int unique, int dropped, int collapsed) {
        return "LOGS (" + shown + " of " + unique + " distinct messages shown in time order; "
                + dropped + " lower-priority messages omitted; " + collapsed + " repeats collapsed)";
    }
}
