package io.github.javatoailab.incidentpilot.llm;

import java.util.Objects;

/**
 * What we send to the model.
 *
 * @param systemPrompt standing instructions for the model (may be null)
 * @param userMessage  the actual question or task
 * @param temperature  0.0 = most repeatable; higher = more varied
 * @param maxTokens    hard cap on the length of the reply, which also caps its cost
 */
public record ChatRequest(String systemPrompt, String userMessage, double temperature, int maxTokens) {

    public ChatRequest {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }
        if (temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("temperature must be between 0.0 and 2.0");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
    }

    /** A sensible production default: deterministic-leaning and length-capped. */
    public static ChatRequest of(String systemPrompt, String userMessage) {
        return new ChatRequest(systemPrompt, Objects.requireNonNull(userMessage), 0.0, 512);
    }
}
