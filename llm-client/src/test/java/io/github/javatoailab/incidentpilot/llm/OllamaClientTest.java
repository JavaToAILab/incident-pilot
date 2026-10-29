package io.github.javatoailab.incidentpilot.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the client against a fake model server, so they run anywhere, with no Ollama
 * and no network. The model's text is non-deterministic; our handling of HTTP is not.
 */
class OllamaClientTest {

    private static final String OK = """
            {"model":"llama3.2","message":{"role":"assistant","content":"A circuit breaker stops calls to a failing service."},
             "done":true,"done_reason":"stop","prompt_eval_count":31,"eval_count":12}
            """;

    private HttpServer server;
    private final Deque<Reply> replies = new ArrayDeque<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    record Reply(int status, String body, long delayMs) {}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            calls.incrementAndGet();
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Reply reply = replies.isEmpty() ? new Reply(200, OK, 0) : replies.poll();
            try {
                Thread.sleep(reply.delayMs());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException ignored) {
                // client gave up (timeout test)
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OllamaClient client(int maxAttempts, Duration requestTimeout) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new OllamaClient(new LlmConfig(base, "llama3.2", Duration.ofSeconds(2), requestTimeout,
                new RetryPolicy(maxAttempts, Duration.ofMillis(10), Duration.ofMillis(50))));
    }

    @Test
    void parsesContentAndTokenUsage() {
        ChatResponse r = client(3, Duration.ofSeconds(5)).chat(ChatRequest.of("Be brief.", "What is a circuit breaker?"));

        assertEquals("A circuit breaker stops calls to a failing service.", r.content());
        assertEquals(31, r.inputTokens());
        assertEquals(12, r.outputTokens());
        assertEquals("stop", r.stopReason());
        assertFalse(r.truncated());
        assertEquals(1, r.attempts());
    }

    @Test
    void sendsModelOptionsAndSystemPrompt() {
        client(1, Duration.ofSeconds(5)).chat(new ChatRequest("Be brief.", "Hi", 0.0, 64));

        String body = lastBody.get();
        assertTrue(body.contains("\"stream\":false"), body);
        assertTrue(body.contains("\"role\":\"system\""), body);
        assertTrue(body.contains("\"num_predict\":64"), body);
        assertTrue(body.contains("\"temperature\":0.0"), body);
    }

    @Test
    void retriesTransientFailuresThenSucceeds() {
        replies.add(new Reply(503, "{\"error\":\"server busy\"}", 0));
        replies.add(new Reply(429, "{\"error\":\"too many requests\"}", 0));

        ChatResponse r = client(3, Duration.ofSeconds(5)).chat(ChatRequest.of(null, "Hello"));

        assertEquals(3, r.attempts());
        assertEquals(3, calls.get());
    }

    @Test
    void doesNotRetryClientErrors() {
        replies.add(new Reply(404, "{\"error\":\"model 'llama9' not found\"}", 0));

        LlmException e = assertThrows(LlmException.class,
                () -> client(3, Duration.ofSeconds(5)).chat(ChatRequest.of(null, "Hello")));

        assertEquals(404, e.statusCode());
        assertTrue(e.getMessage().contains("not found"), e.getMessage());
        assertEquals(1, calls.get(), "a 404 will never fix itself; retrying only wastes time");
    }

    @Test
    void givesUpAfterMaxAttempts() {
        for (int i = 0; i < 5; i++) {
            replies.add(new Reply(500, "{\"error\":\"boom\"}", 0));
        }

        LlmException e = assertThrows(LlmException.class,
                () -> client(3, Duration.ofSeconds(5)).chat(ChatRequest.of(null, "Hello")));

        assertTrue(e.getMessage().contains("after 3 attempts"), e.getMessage());
        assertEquals(3, calls.get());
    }

    @Test
    void timesOutInsteadOfHangingForever() {
        replies.add(new Reply(200, OK, 1_500));

        LlmException e = assertThrows(LlmException.class,
                () -> client(1, Duration.ofMillis(300)).chat(ChatRequest.of(null, "Hello")));

        assertEquals(-1, e.statusCode());
    }

    @Test
    void flagsTruncatedReplies() {
        replies.add(new Reply(200, OK.replace("\"stop\"", "\"length\""), 0));

        assertTrue(client(1, Duration.ofSeconds(5)).chat(ChatRequest.of(null, "Hello")).truncated());
    }

    @Test
    void rejectsMalformedSuccessWithoutRetrying() {
        replies.add(new Reply(200, "{\"unexpected\":true}", 0));

        assertThrows(LlmException.class, () -> client(3, Duration.ofSeconds(5)).chat(ChatRequest.of(null, "Hello")));
        assertEquals(1, calls.get());
    }

    @Test
    void validatesRequestsBeforeSpendingTokens() {
        assertThrows(IllegalArgumentException.class, () -> new ChatRequest(null, " ", 0.0, 10));
        assertThrows(IllegalArgumentException.class, () -> new ChatRequest(null, "Hi", 3.0, 10));
        assertThrows(IllegalArgumentException.class, () -> new ChatRequest(null, "Hi", 0.0, 0));
    }
}
