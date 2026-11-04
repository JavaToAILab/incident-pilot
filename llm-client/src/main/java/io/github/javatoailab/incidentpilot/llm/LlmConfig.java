package io.github.javatoailab.incidentpilot.llm;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Everything environment-specific, in one place. Nothing here is hard-coded in the client.
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code LLM_BASE_URL} (default {@code http://localhost:11434}, a local Ollama)</li>
 *   <li>{@code LLM_MODEL} (default {@code llama3.2})</li>
 *   <li>{@code LLM_CONNECT_TIMEOUT_SECONDS} (default 5)</li>
 *   <li>{@code LLM_REQUEST_TIMEOUT_SECONDS} (default 60)</li>
 *   <li>{@code LLM_MAX_ATTEMPTS} (default 3)</li>
 *   <li>{@code LLM_CONTEXT_WINDOW} (default 8192): tokens the server allocates per request,
 *       input and output together. Ollama otherwise falls back to its own, much smaller, default.</li>
 * </ul>
 */
public record LlmConfig(URI baseUrl, String model, Duration connectTimeout, Duration requestTimeout,
                        RetryPolicy retry, int contextWindow) {

    public static final int DEFAULT_CONTEXT_WINDOW = 8192;

    public LlmConfig {
        if (contextWindow <= 0) {
            throw new IllegalArgumentException("contextWindow must be positive");
        }
    }

    /** Article 3's constructor, kept so existing callers compile; uses the default context window. */
    public LlmConfig(URI baseUrl, String model, Duration connectTimeout, Duration requestTimeout, RetryPolicy retry) {
        this(baseUrl, model, connectTimeout, requestTimeout, retry, DEFAULT_CONTEXT_WINDOW);
    }

    public static LlmConfig fromEnv() {
        return fromMap(System.getenv());
    }

    static LlmConfig fromMap(Map<String, String> env) {
        RetryPolicy defaults = RetryPolicy.defaults();
        return new LlmConfig(
                URI.create(env.getOrDefault("LLM_BASE_URL", "http://localhost:11434")),
                env.getOrDefault("LLM_MODEL", "llama3.2"),
                Duration.ofSeconds(Long.parseLong(env.getOrDefault("LLM_CONNECT_TIMEOUT_SECONDS", "5"))),
                Duration.ofSeconds(Long.parseLong(env.getOrDefault("LLM_REQUEST_TIMEOUT_SECONDS", "60"))),
                new RetryPolicy(
                        Integer.parseInt(env.getOrDefault("LLM_MAX_ATTEMPTS", String.valueOf(defaults.maxAttempts()))),
                        defaults.baseDelay(),
                        defaults.maxDelay()),
                Integer.parseInt(env.getOrDefault("LLM_CONTEXT_WINDOW", String.valueOf(DEFAULT_CONTEXT_WINDOW))));
    }
}
