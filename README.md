# IncidentPilot

**An AI incident copilot for a microservices platform, built one module per article in the [Java to AI](https://medium.com/@abhinav.neo/list/java-to-ai-70943ef97ee9) series.**

Alerts and log events stream in. IncidentPilot summarizes each incident as a structured record, finds similar past incidents and the matching runbook, queries metrics, logs and deploy history through tools, and streams a triage recommendation to a live dashboard. A human approves every action.

It's a real-time application on purpose: production AI is mostly integration, reliability, security and cost, and that is where Java engineers already have an edge.

## Where it's going

```
Alert sources ──▶ Alert stream (Kafka) ──▶ Ingest and summarize
                                                   │
                                                   ▼
Knowledge (pgvector) ◀──── Triage agent ────▶ Ops tools (MCP server)
                                │
                                ▼
                         Live dashboard (SSE, human approves)

Production layer: model gateway · guardrails · circuit breakers · evals · OpenTelemetry
```

## Follow along by article

Each build article ends with a git tag. Check out the tag to get exactly the code the article describes:

```bash
git clone https://github.com/JavaToAILab/incident-pilot.git
cd incident-pilot
git checkout article-03
```

| Tag | Article | What it adds |
|---|---|---|
| `article-03` | [Your First LLM Call From Java, Written Like Production Code](https://medium.com/@abhinav.neo/your-first-llm-call-from-java-written-like-production-code-bfc809df2a61) | `llm-client`: the model client with timeouts, bounded retries, typed errors and token tracking |
| `article-05` | Tokens and Context Windows as Capacity Planning | A context budget for fitting alerts and logs into a prompt |
| `article-07` | Structured Output With Java Records | The `IncidentSummary` record |
| `article-09` | Real Time: Streaming Alerts In and Answers Out | Kafka ingestion and server-sent events |
| … | | The rest of the roadmap: RAG over runbooks, tools, MCP, agents, evals, observability |

Future tags are planned, not yet published. This table is updated as each article goes live.

## Modules

| Module | Since | Job |
|---|---|---|
| `llm-client` | Article 3 | The interface the rest of the app depends on, and an Ollama implementation built on the JDK `HttpClient` |

## Run it

Requirements: Java 21, Maven, and [Ollama](https://ollama.com) for a local model. No API key needed.

```bash
ollama pull llama3.2
mvn test                                   # runs against a fake model server, no Ollama needed
mvn -q -pl llm-client compile exec:java -Dexec.args="Summarize: payment-service p99 latency jumped to 4s after deploy 1.42"
```

Configuration is through environment variables (`LLM_BASE_URL`, `LLM_MODEL`, timeouts, retry attempts); see `LlmConfig`.

Part of [JavaToAILab](https://github.com/JavaToAILab). Questions, corrections and "you got this wrong" are welcome as issues.
