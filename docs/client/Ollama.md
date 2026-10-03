# Using Ollama

[Ollama](https://ollama.com) 0.35 and later serve
[Jev-style decision models](https://ollama.com/blog/ollama-now-supports-jev-style-decision-models)
on the same `POST /v1/systemone` wire protocol as Jev. `TypeSafeClient`, and everything built
on it, runs against a local Ollama unchanged: no API key, no network round trip, and your
data stays on the machine.

!!! warning "A different model, not a different endpoint"
    The protocol matches closely; the model is another one. The thresholds in this project's
    examples and tests were tuned on Jev, so check your criteria against the Ollama model you
    pick before relying on its decisions. See [how it compares](#how-it-compares).

## Run a decision model

```bash
ollama pull nimble        # or tev1, tev1:0.8b
```

| Model | Size | From |
|---|---|---|
| `nimble` | 9B (a 9.5 GB download) | Bespoke Labs |
| `tev1` | 4B | Together AI |
| `tev1:0.8b` | 0.8B | Together AI |

Ollama serves on `http://localhost:11434`. The model is loaded on the first request, so allow
a generous timeout for that one.

## Point the client at it

Ollama accepts any API key, but the client needs a non-empty one. Name the model: Ollama has
no `jev-latest`.

=== "Spring Boot"

    ```properties
    spring.ai.typesafe.base-url=http://localhost:11434
    spring.ai.typesafe.api-key=ollama
    spring.ai.typesafe.model=nimble
    spring.ai.typesafe.timeout=60s
    spring.ai.typesafe.retry.total-timeout=150s
    ```

    The retry budget must stay above the timeout, or no retry can ever fit in it. The
    [Ollama demo](../demos.md#ollamasystemonedemoapplication) uses exactly this, as an
    `ollama` profile.

=== "Plain Java"

    ```java
    TypeSafeClient client = TypeSafeClient.builder()
            .baseUrl("http://localhost:11434")
            .apiKey("ollama")
            .defaultModel("nimble")
            .timeout(Duration.ofSeconds(60))
            .build();
    ```

    Or leave all three out and export `TYPESAFE_BASE_URL=http://localhost:11434`,
    `TYPESAFE_API_KEY=ollama` and `TYPESAFE_DEFAULT_MODEL=nimble`, which the official Python
    and JavaScript SDKs read too.

## What differs

Measured against Ollama 0.35.0 with `nimble`:

| Area | Ollama | Effect |
|---|---|---|
| `model` | required, and must name a pulled model; `jev-latest` is not one | Set `spring.ai.typesafe.model` or `defaultModel(...)`. `TypeSafeModels` constants return 404. |
| `GET /v1/models` | Ollama's OpenAI-style list, `{"object": "list", "data": [...]}` | `listModels()` returns an **empty list** rather than failing. List models with `ollama list`. |
| Noul without instructions | rejected, 400 | Give every noul `instructions`, even when it has `whenTrue` / `whenFalse` criteria. |
| Request id | not sent | `requestId()` is `null`. |
| Authentication | none; any key, or none, is accepted | Keep Ollama on localhost, or put it behind something that checks. |
| Error body | `{"error": "..."}`, no error type | The status still maps to the right exception, and `errorMessage()` reads the `error` text. `errorType()` is `null`. |
| Validation | a `null` state is 400; more than 64 questions is 400 | Don't rely on the exact exception subtype. |

Everything else, including the noul, choice and score answers, probabilities, confidence,
score legends and token usage, has the same shape as Jev's.

## How it compares

Running this project's [demos](../demos.md) against `nimble` and against Jev, on the same
inputs:

| Workload | `nimble` | Jev |
|---|---|---|
| Plausibility (−125 °C or −255 °C in Paris) | rated 0.00–0.02, impossible; 15 °C at 0.99 | rated 0.02–0.20 |
| Ticket triage | confident: departments at 0.81–0.95, all routed automatically, but the refund ticket went to `auto_close` | the refund ticket routed to billing |
| RAG: a passage contradicting the others | `INCLUDED`, reranked level with the right one | `CONFLICTING` |
| Tool search on four paraphrased requests | 4 of 4 | 4 of 4 |
| Cascade: planted extraction errors caught | 1 of 2; missed an invented purchase-order number | 2 of 2 |
| Guardrails: pass, jailbreak, self-harm, harmful reply | `PASS`, `BLOCK`, `SUPPORT`, `BLOCK` | the same |

In short:

- `nimble` is decisive, and strong on checks that need world knowledge. Unlike
  [Laya](Laya.md#criteria-that-need-world-knowledge), it rejects an impossible temperature
  outright, even when the answer hedges.
- It misses subtler signals: a passage that contradicts the others, a value invented from
  nothing.
- **It is sensitive to the order of fields in the state.** The tool-search question "does
  any tool apply?" for "bill Acme Corp for last month" scores 0.95 with the request first and
  0.42 with the tool list first, the two sides of the 0.5 threshold. The SDK's own components
  send a fixed order, request first. For your own state, use `JsonContent.object(...)`; see
  [key order matters](TypeSafeClient.md#key-order-matters).
- **Several questions in one call are not cheaper.** Warm, on an Apple M3 Max, with the
  same ticket as state: a noul, a choice or a score alone takes about 60 ms each, while all
  three in one call take about 410 ms. That is slower than three separate calls, the
  opposite of Jev, which answers a call's questions in parallel against a state it reads
  once. On Ollama, measure before merging questions into one call for speed.

Use Ollama where data locality, offline work or cost matter more than Jev's accuracy on
subtle signals, and set thresholds from measurements against the model you run.

## Integration tests against Ollama

The live ITs follow `TYPESAFE_BASE_URL`, but they pin `TypeSafeModels.JEV_LATEST` as the
model, which Ollama does not have. So they don't run against Ollama as they are.

## See Also

- [Using Laya](Laya.md): another local, open-source System One server
- [TypeSafeClient](TypeSafeClient.md): builder settings and environment variables
- [Spring Boot Starter](SpringBootStarter.md): properties
- [Demos](../demos.md#ollamasystemonedemoapplication): the Ollama demo
