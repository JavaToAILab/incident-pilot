package io.github.javatoailab.incidentpilot.prompt;

/** A template with every input filled in, still carrying the identity of the template it came from. */
public record RenderedPrompt(String text, PromptRef ref) {
}
