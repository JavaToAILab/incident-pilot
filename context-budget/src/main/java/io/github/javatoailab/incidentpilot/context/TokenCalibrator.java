package io.github.javatoailab.incidentpilot.context;

import io.github.javatoailab.incidentpilot.llm.ChatResponse;

/**
 * Learns the real characters-per-token ratio from the model's own usage reports.
 *
 * <p>Estimates drift; measurements don't. Every response from {@code llm-client} carries the
 * prompt's real token count, so feed it back here and estimate with what you observe.
 */
public final class TokenCalibrator {

    private final double alpha;
    private double charsPerToken;
    private long observations;

    /**
     * @param initialCharsPerToken starting guess, e.g. {@link HeuristicTokenCounter#ENGLISH_CHARS_PER_TOKEN}
     * @param alpha                weight of each new observation (0 &lt; alpha &le; 1)
     */
    public TokenCalibrator(double initialCharsPerToken, double alpha) {
        if (initialCharsPerToken <= 0 || alpha <= 0 || alpha > 1) {
            throw new IllegalArgumentException("need initialCharsPerToken > 0 and 0 < alpha <= 1");
        }
        this.charsPerToken = initialCharsPerToken;
        this.alpha = alpha;
    }

    public synchronized void observe(int promptChars, int actualPromptTokens) {
        if (promptChars <= 0 || actualPromptTokens <= 0) {
            return; // the server didn't report usage; nothing to learn
        }
        double observed = (double) promptChars / actualPromptTokens;
        charsPerToken = observations == 0 ? observed : alpha * observed + (1 - alpha) * charsPerToken;
        observations++;
    }

    /** Convenience: learn from a request and the response the model sent back. */
    public void observe(IncidentContext context, ChatResponse response) {
        observe(context.systemPrompt().length() + context.userMessage().length(), response.inputTokens());
    }

    public synchronized double charsPerToken() {
        return charsPerToken;
    }

    public synchronized long observations() {
        return observations;
    }

    /** A counter that uses what has been observed so far. */
    public synchronized HeuristicTokenCounter counter() {
        return new HeuristicTokenCounter(charsPerToken);
    }
}
