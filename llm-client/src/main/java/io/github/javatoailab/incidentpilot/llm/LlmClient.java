package io.github.javatoailab.incidentpilot.llm;

/**
 * The only thing the rest of your application should know about a model.
 *
 * <p>Treat an LLM like any other remote dependency: hide it behind an interface,
 * so the provider, model and transport can change without touching callers.
 */
public interface LlmClient {

    /**
     * Sends one chat request and returns the model's reply.
     *
     * @throws LlmException if the call fails after all retries, or fails in a way
     *                      that retrying cannot fix (bad request, unknown model)
     */
    ChatResponse chat(ChatRequest request);
}
