package io.github.javatoailab.incidentpilot.llm;

/**
 * Run with: {@code mvn -q compile exec:java -Dexec.args="Explain a circuit breaker in two sentences."}
 */
public final class App {

    public static void main(String[] args) {
        String question = args.length > 0
                ? String.join(" ", args)
                : "In two sentences, what is a circuit breaker in distributed systems?";

        LlmClient llm = new OllamaClient(LlmConfig.fromEnv());
        ChatRequest request = ChatRequest.of(
                "You are a concise assistant for senior Java engineers. Answer in plain English.",
                question);

        try {
            ChatResponse response = llm.chat(request);
            System.out.println(response.content());
            System.out.printf("%n[%s | %d in / %d out tokens | %d ms | %d attempt(s)%s]%n",
                    response.model(), response.inputTokens(), response.outputTokens(),
                    response.latency().toMillis(), response.attempts(),
                    response.truncated() ? " | TRUNCATED: raise maxTokens" : "");
        } catch (LlmException e) {
            System.err.println("Model call failed: " + e.getMessage());
            System.err.println("Is Ollama running? Try: ollama serve  and  ollama pull llama3.2");
            System.exit(1);
        }
    }
}
