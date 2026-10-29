package io.github.javatoailab.incidentpilot.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * Calls Ollama's {@code /api/chat} endpoint with the JDK's own HTTP client.
 *
 * <p>No AI framework on purpose: before Spring AI or LangChain4j hides it, it is worth
 * seeing that an LLM call is an HTTP POST, and that everything that makes it production-grade
 * (timeouts, retries, error classification, usage tracking) is ordinary engineering.
 */
public final class OllamaClient implements LlmClient {

    private static final System.Logger LOG = System.getLogger(OllamaClient.class.getName());

    private final LlmConfig config;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public OllamaClient(LlmConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .build();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        String body = toJson(request);
        RetryPolicy retry = config.retry();
        LlmException last = null;

        for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
            if (attempt > 1) {
                sleep(retry.delayBeforeRetry(attempt - 1));
            }
            long start = System.nanoTime();
            try {
                ChatResponse response = send(body, attempt, start);
                // One structured line per call. Note what is NOT logged: the prompt and the reply,
                // which may contain customer data.
                LOG.log(System.Logger.Level.INFO,
                        "llm.call model={0} attempts={1} latencyMs={2} inputTokens={3} outputTokens={4} stop={5}",
                        response.model(), response.attempts(), response.latency().toMillis(),
                        response.inputTokens(), response.outputTokens(), response.stopReason());
                return response;
            } catch (LlmException e) {
                last = e;
                LOG.log(System.Logger.Level.WARNING, "llm.call attempt {0}/{1} failed: {2}",
                        attempt, retry.maxAttempts(), e.getMessage());
                if (!e.isRetryable()) {
                    throw e;
                }
            }
        }
        throw new LlmException("LLM call failed after " + retry.maxAttempts() + " attempts: " + last.getMessage(),
                false, last.statusCode(), last);
    }

    private ChatResponse send(String body, int attempt, long start) {
        HttpRequest httpRequest = HttpRequest.newBuilder(config.baseUrl().resolve("/api/chat"))
                .timeout(config.requestTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> httpResponse;
        try {
            httpResponse = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new LlmException("Timed out after " + config.requestTimeout(), true, -1, e);
        } catch (IOException e) {
            throw new LlmException("Could not reach model server at " + config.baseUrl() + ": " + e, true, -1, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted while calling the model", false, -1, e);
        }

        int status = httpResponse.statusCode();
        if (status != 200) {
            throw new LlmException("HTTP " + status + ": " + errorMessage(httpResponse.body()),
                    LlmException.isRetryableStatus(status), status);
        }
        return parse(httpResponse.body(), attempt, Duration.ofNanos(System.nanoTime() - start));
    }

    String toJson(ChatRequest request) {
        ObjectNode root = json.createObjectNode();
        root.put("model", config.model());
        root.put("stream", false);
        ArrayNode messages = root.putArray("messages");
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.addObject().put("role", "system").put("content", request.systemPrompt());
        }
        messages.addObject().put("role", "user").put("content", request.userMessage());
        root.putObject("options")
                .put("temperature", request.temperature())
                .put("num_predict", request.maxTokens());
        return root.toString();
    }

    private ChatResponse parse(String body, int attempt, Duration latency) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode content = root.path("message").path("content");
            if (content.isMissingNode()) {
                // A 200 with an unexpected shape is a contract violation, not a transient blip.
                throw new LlmException("Response had no message.content", false, 200);
            }
            return new ChatResponse(
                    content.asText(),
                    root.path("model").asText(config.model()),
                    root.path("prompt_eval_count").asInt(0),
                    root.path("eval_count").asInt(0),
                    root.path("done_reason").asText("unknown"),
                    latency,
                    attempt);
        } catch (IOException e) {
            throw new LlmException("Response was not valid JSON", false, 200, e);
        }
    }

    private String errorMessage(String body) {
        try {
            return json.readTree(body).path("error").asText(body);
        } catch (IOException e) {
            return body;
        }
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted during retry backoff", false, -1, e);
        }
    }
}
