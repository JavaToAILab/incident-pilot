package io.github.javatoailab.incidentpilot.structured;

import java.util.ArrayList;
import java.util.List;

/**
 * Rules a schema cannot express, because they depend on the input. A reply can be perfectly shaped
 * JSON and still quote a log line that was never in the logs.
 */
public final class IncidentSummaryChecks {

    public static final int MAX_EVIDENCE_LINES = 5;

    /** Shorter than this, a quote like "ERROR" matches almost any log and proves nothing. */
    public static final int MIN_EVIDENCE_CHARS = 20;

    private final int maxSummaryWords;

    public IncidentSummaryChecks(int maxSummaryWords) {
        this.maxSummaryWords = maxSummaryWords;
    }

    /**
     * @param summary     a reply that already passed the JSON contract
     * @param userMessage exactly what was sent to the model as data (the alert and the logs)
     * @return every rule the summary breaks; empty if it is acceptable
     */
    public List<String> check(IncidentSummary summary, String userMessage) {
        List<String> violations = new ArrayList<>();
        List<String> sentLines = (userMessage == null ? "" : userMessage).lines().map(IncidentSummaryChecks::normalize).toList();

        if (summary.evidence().size() > MAX_EVIDENCE_LINES) {
            violations.add("\"evidence\" has " + summary.evidence().size() + " lines; the limit is " + MAX_EVIDENCE_LINES);
        }
        for (int i = 0; i < summary.evidence().size(); i++) {
            String quote = normalize(summary.evidence().get(i));
            if (quote.length() < MIN_EVIDENCE_CHARS) {
                violations.add("\"evidence\"[" + i + "] is too short to identify a log line; copy the whole line");
            } else if (sentLines.stream().noneMatch(line -> line.contains(quote))) {
                // Each quote must come from one line of what was sent. Part of a line is fine (models
                // often drop the timestamp); a line that was never there is not.
                violations.add("\"evidence\"[" + i + "] is not a line from the LOGS; copy log lines exactly");
            }
        }
        int words = summary.summary().strip().split("\\s+").length;
        if (words > maxSummaryWords) {
            violations.add("\"summary\" has " + words + " words; the limit is " + maxSummaryWords);
        }
        if (summary.confidence() == Confidence.HIGH && summary.evidence().isEmpty()) {
            violations.add("\"confidence\" is \"high\" but no evidence was quoted");
        }
        return violations;
    }

    /** Whitespace differences are not worth a retry; anything else is. */
    private static String normalize(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }
}
