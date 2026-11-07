# IncidentPilot

**An AI incident copilot for a microservices platform, built one module per article in the [Java to AI](https://medium.com/@abhinav.neo/list/java-to-ai-70943ef97ee9) series.**

Alerts and log events stream in. IncidentPilot summarizes each incident as a structured record, finds similar past incidents and the matching runbook, queries metrics, logs and deploy history through tools, and streams a triage recommendation to a live dashboard. A human approves every action.

New here? Start with [Meet IncidentPilot: An LLM Is a Non-Deterministic Dependency](https://medium.com/@abhinav.neo/meet-incidentpilot-an-llm-is-a-non-deterministic-dependency-97de8825c3c6), which introduces the app and the six design rules it follows.

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
git checkout article-06
```

| Tag | Article | What it adds |
|---|---|---|
| [`article-03`](https://github.com/JavaToAILab/incident-pilot/tree/article-03) | [Your First LLM Call From Java, Written Like Production Code](https://medium.com/@abhinav.neo/your-first-llm-call-from-java-written-like-production-code-bfc809df2a61) | `llm-client`: the model client with timeouts, bounded retries, typed errors and token tracking |
| [`article-05`](https://github.com/JavaToAILab/incident-pilot/tree/article-05) | [Tokens and Context Windows as Capacity Planning](https://medium.com/@abhinav.neo/tokens-and-context-windows-as-capacity-planning-1626d8652014) | `context-budget`: real token counting, a context budget, and log selection that fits an incident into the window; `llm-client` now sets the context window explicitly |
| [`article-06`](https://github.com/JavaToAILab/incident-pilot/tree/article-06) | Prompts Are API Contracts | `prompt-contracts`: versioned, immutable prompt files with declared inputs, a lock file, and a prompt ref logged with every model call |
| `article-07` | Structured Output With Java Records | The `IncidentSummary` record |
| `article-09` | Real Time: Streaming Alerts In and Answers Out | Kafka ingestion and server-sent events |
| … | | The rest of the roadmap: RAG over runbooks, tools, MCP, agents, evals, observability |

Future tags are planned, not yet published. This table is updated as each article goes live.

## Modules

| Module | Since | Job |
|---|---|---|
| `llm-client` | Article 3 | The interface the rest of the app depends on, and an Ollama implementation built on the JDK `HttpClient` |
| `context-budget` | Article 5 | Token counting (heuristic and a real BPE tokenizer), a context budget, and an incident context builder that collapses repeated log messages and keeps errors and their causes first |
| `prompt-contracts` | Article 6 | Prompt templates as versioned contracts: declared variables checked at load and render time, released versions locked by SHA-256, and a ref such as `incident-summary@v2#edd77dee` on every request |

## Changing a prompt

Prompts live in `prompt-contracts/src/main/resources/prompts/<id>/v<n>.prompt` and are treated like a published API version: once released, a version never changes.

1. Copy the latest version to the next number (`v2.prompt` to `v3.prompt`) and bump `version:` in its header.
2. Declare every `{{placeholder}}` the new body uses in `variables:`. Undeclared or unused variables fail at load time.
3. Run `mvn -q -pl prompt-contracts exec:java -Dexec.args=--lock` from the repo root and add the new line to `prompts.lock`.
4. Callers get the latest version by id, or pin one (`incident-summary@2`). Every request logs which one it used.

Editing a released file instead fails the build: its hash no longer matches the lock.

## Run it

Requirements: Java 21, Maven, and [Ollama](https://ollama.com) for a local model. No API key needed.

```bash
ollama pull llama3.2
mvn test                                   # runs against a fake model server, no Ollama needed
mvn -q install -DskipTests                 # build all modules once
mvn -q -pl llm-client exec:java -Dexec.args="Summarize: payment-service p99 latency jumped to 4s after deploy 1.42"
mvn -q -pl context-budget exec:java        # token report on a sample incident: chars per token, what fits in 4K / 8K / 32K
mvn -q -pl prompt-contracts exec:java      # every released prompt version: inputs, token cost, and the rendered system prompt
```

Configuration is through environment variables (`LLM_BASE_URL`, `LLM_MODEL`, `LLM_CONTEXT_WINDOW`, timeouts, retry attempts); see `LlmConfig`.

Part of [JavaToAILab](https://github.com/JavaToAILab). Questions, corrections and "you got this wrong" are welcome as issues.
