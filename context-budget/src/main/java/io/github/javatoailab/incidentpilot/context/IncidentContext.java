package io.github.javatoailab.incidentpilot.context;

import io.github.javatoailab.incidentpilot.llm.ChatRequest;

/**
 * A prompt that is guaranteed (by estimate) to fit the budget, plus a record of what was left out.
 *
 * @param estimatedInputTokens tokens the prompt is expected to cost, by the builder's counter
 * @param linesIncluded        unique log lines that made it into the prompt
 * @param linesDropped         unique log lines left out for lack of space
 * @param repeatsCollapsed     duplicate lines folded into a "repeated Nx" note
 */
public record IncidentContext(
        String systemPrompt,
        String userMessage,
        ContextBudget budget,
        int estimatedInputTokens,
        int linesIncluded,
        int linesDropped,
        int repeatsCollapsed) {

    /** A deterministic-leaning request whose reply is capped at the budget's output reservation. */
    public ChatRequest toChatRequest() {
        return new ChatRequest(systemPrompt, userMessage, 0.0, budget.reservedForOutput());
    }

    public boolean droppedAnything() {
        return linesDropped > 0;
    }
}
