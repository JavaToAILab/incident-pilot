package io.github.javatoailab.incidentpilot.context;

/**
 * The parts of the prompt that can never be dropped (system prompt and alert) do not fit.
 * Fail loudly: silently sending a truncated prompt is worse than not calling the model.
 */
public class ContextBudgetExceededException extends RuntimeException {

    private final int requiredTokens;
    private final int availableTokens;

    public ContextBudgetExceededException(int requiredTokens, int availableTokens) {
        super("Fixed prompt needs ~" + requiredTokens + " tokens but the input budget is " + availableTokens);
        this.requiredTokens = requiredTokens;
        this.availableTokens = availableTokens;
    }

    public int requiredTokens() {
        return requiredTokens;
    }

    public int availableTokens() {
        return availableTokens;
    }
}
