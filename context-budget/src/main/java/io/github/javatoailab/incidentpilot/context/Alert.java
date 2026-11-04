package io.github.javatoailab.incidentpilot.context;

import java.time.Instant;
import java.util.Objects;

/**
 * The alert that opened the incident. It always goes into the prompt in full.
 */
public record Alert(String service, String title, String description, Instant firedAt) {

    public Alert {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(firedAt, "firedAt");
        description = description == null ? "" : description;
    }

    String render() {
        return "ALERT\n"
                + "service: " + service + "\n"
                + "title: " + title + "\n"
                + "fired_at: " + firedAt + "\n"
                + (description.isBlank() ? "" : "description: " + description + "\n");
    }
}
