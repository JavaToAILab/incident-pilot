package io.github.javatoailab.incidentpilot.context;

/**
 * Capacity planning for one model call.
 *
 * <p>A context window is shared: the prompt AND the reply must fit in it. So the budget for
 * input is the window, minus what you reserve for the answer, minus a safety margin for
 * the gap between your token estimate and the model's real tokenizer.
 *
 * @param contextWindow     total tokens the model is configured for (Ollama's {@code num_ctx})
 * @param reservedForOutput tokens kept free for the reply; also the reply's hard cap
 * @param safetyMargin      fraction of the input budget held back for estimation error (0.0 to 0.5)
 */
public record ContextBudget(int contextWindow, int reservedForOutput, double safetyMargin) {

    public ContextBudget {
        if (contextWindow <= 0) {
            throw new IllegalArgumentException("contextWindow must be positive");
        }
        if (reservedForOutput <= 0 || reservedForOutput >= contextWindow) {
            throw new IllegalArgumentException("reservedForOutput must be positive and smaller than the window");
        }
        if (safetyMargin < 0.0 || safetyMargin > 0.5) {
            throw new IllegalArgumentException("safetyMargin must be between 0.0 and 0.5");
        }
    }

    /** IncidentPilot's default: an 8K window, 1K reserved for the summary, 10% margin. */
    public static ContextBudget defaults() {
        return new ContextBudget(8192, 1024, 0.10);
    }

    /** Tokens available for the prompt after the output reservation and the margin. */
    public int inputBudget() {
        return (int) Math.floor((contextWindow - reservedForOutput) * (1.0 - safetyMargin));
    }
}
