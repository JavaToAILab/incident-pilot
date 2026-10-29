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
 * </ul>
 */
public record LlmConfig(URI baseUrl, String model, Duration connectTimeout, Duration requestTimeout, RetryPolicy retry) {

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
                        defaults.maxDelay()));
    }
}
