package io.github.javatoailab.incidentpilot.structured;

import java.util.List;
import java.util.Objects;

/**
 * What IncidentPilot accepts from the model for one incident. This record is the source of truth for
 * the output contract: the JSON Schema sent to the model is generated from it, and a test checks that
 * the prompt asks for exactly these fields.
 *
 * <p>Field names are written in JSON as snake_case ({@code suspectedComponent} is
 * {@code suspected_component}), matching prompt v2.
 *
 * @param severity           one of the team's severity levels
 * @param suspectedComponent the service, dependency or resource most likely at fault, or "unknown"
 * @param summary            what is happening, in plain words
 * @param evidence           log lines copied from the input that support the summary
 * @param confidence         how sure the model is
 * @param unknowns           questions the alert and logs cannot answer
 */
public record IncidentSummary(
        Severity severity,
        String suspectedComponent,
        String summary,
        List<String> evidence,
        Confidence confidence,
        List<String> unknowns) {

    public IncidentSummary {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(confidence, "confidence");
        if (suspectedComponent == null || suspectedComponent.isBlank()) {
            throw new IllegalArgumentException("suspected_component must not be blank (use \"unknown\")");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        // Defensive copies: once accepted, a summary cannot be changed by whoever holds the lists.
        evidence = List.copyOf(evidence);
        unknowns = List.copyOf(unknowns);
    }
}
