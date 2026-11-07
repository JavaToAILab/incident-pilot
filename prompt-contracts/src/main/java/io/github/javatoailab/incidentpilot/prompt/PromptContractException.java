package io.github.javatoailab.incidentpilot.prompt;

/** A prompt broke its contract: a missing input, an unexpected input, or a released prompt that changed. */
public final class PromptContractException extends RuntimeException {

    public PromptContractException(String message) {
        super(message);
    }
}
