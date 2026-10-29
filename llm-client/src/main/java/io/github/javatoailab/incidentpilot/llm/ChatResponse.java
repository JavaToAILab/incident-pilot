package io.github.javatoailab.incidentpilot.llm;

import java.time.Duration;

/**
 * What we get back. Note that the text is only one field: in production you also
 * need to know what it cost, how long it took, and why the model stopped.
 *
 * @param content      the model's reply
 * @param model        the model that actually answered
 * @param inputTokens  tokens in the prompt (what you pay to send)
 * @param outputTokens tokens in the reply (what you pay to receive)
 * @param stopReason   why generation ended, e.g. "stop" or "length" (hit maxTokens)
 * @param latency      wall-clock time for the successful attempt
 * @param attempts     how many attempts it took, including retries
 */
public record ChatResponse(
        String content,
        String model,
        int inputTokens,
        int outputTokens,
        String stopReason,
        Duration latency,
        int attempts) {

    /** True when the reply was cut off by the token limit rather than finishing naturally. */
    public boolean truncated() {
        return "length".equals(stopReason);
    }
}
