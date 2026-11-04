package io.github.javatoailab.incidentpilot.context;

/**
 * Counts how many tokens a piece of text will cost.
 *
 * <p>Every model family has its own tokenizer, so any count made before the call is an
 * estimate. The model's response reports the real number; see {@link TokenCalibrator}.
 */
public interface TokenCounter {

    int count(String text);
}
