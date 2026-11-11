package io.github.javatoailab.incidentpilot.structured;

import java.util.List;

/**
 * The outcome of checking one model reply against a contract. Sealed, so a {@code switch} over it
 * must handle both cases, and the compiler tells you if a third one is ever added.
 */
public sealed interface ParseResult<T> {

    /** The reply met the contract. {@code notes} lists anything that was tolerated, such as a markdown fence. */
    record Valid<T>(T value, List<String> notes) implements ParseResult<T> {
        public Valid {
            notes = List.copyOf(notes);
        }
    }

    /** The reply broke the contract. Every problem found is listed, not just the first. */
    record Invalid<T>(List<String> violations) implements ParseResult<T> {
        public Invalid {
            if (violations.isEmpty()) {
                throw new IllegalArgumentException("an invalid result needs at least one violation");
            }
            violations = List.copyOf(violations);
        }
    }
}
