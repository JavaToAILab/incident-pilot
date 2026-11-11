package io.github.javatoailab.incidentpilot.structured;

import java.util.List;

/**
 * What the rest of IncidentPilot gets back. Callers {@code switch} over it, and the compiler makes
 * them handle all three cases, including the two where there is no summary to show.
 */
public sealed interface SummaryOutcome {

    /** How many model calls this outcome took: 1, or 2 if a repair was needed. */
    int modelCalls();

    /**
     * A summary that passed every check.
     *
     * @param fixedViolations what was wrong with the first reply, if a repair was needed; worth
     *                        logging, because it shows which rules the model keeps breaking
     * @param notes           anything tolerated in the accepted reply, such as a markdown fence
     */
    record Accepted(IncidentSummary summary, int modelCalls, List<String> fixedViolations, List<String> notes)
            implements SummaryOutcome {
        public Accepted {
            fixedViolations = List.copyOf(fixedViolations);
            notes = List.copyOf(notes);
        }

        public boolean repaired() {
            return !fixedViolations.isEmpty();
        }
    }

    /**
     * The model answered, but even after one repair the answer broke the contract.
     *
     * @param firstViolations what was wrong with the first reply
     * @param violations      what was still wrong with the repaired reply
     */
    record Rejected(List<String> firstViolations, List<String> violations, String lastReply, int modelCalls)
            implements SummaryOutcome {
        public Rejected {
            firstViolations = List.copyOf(firstViolations);
            violations = List.copyOf(violations);
        }
    }

    /** The model could not be reached or failed in a way retries did not fix (Article 3's errors). */
    record Unavailable(String reason, int modelCalls) implements SummaryOutcome {
    }
}
