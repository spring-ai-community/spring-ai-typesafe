# Using Laya

[Laya](https://github.com/NandhaKishorM/laya) is an open-source (Apache-2.0) System One
engine. Its `laya-serve` server, added in Laya 0.3.7, exposes the same `POST /v1/systemone`
wire protocol as Jev. That means `TypeSafeClient` and everything built on it can run against a
Laya server on your own machine, unchanged. It is useful for offline development, local
experiments and CI without an API key.

!!! warning "A stand-in, not a replacement"
    The protocol matches closely; the model does not. Laya's answers differ from Jev's, and
    the thresholds in this project's examples and tests were tuned on Jev. Check your
    criteria against Laya before relying on its decisions. See [how it compares](#how-it-compares).

## Run `laya-serve`

```bash
uv venv --python 3.10
uv pip install "laya[serve]"

LAYA_DEVICE=mps \
LAYA_MODELS=english \
LAYA_PRELOAD=1 \
LAYA_API_KEY=local-test \
LAYA_PORT=8002 \
.venv/bin/laya-serve
```

- **`LAYA_DEVICE`:** `mps` on Apple Silicon, `cuda` or `cpu` elsewhere. On CPU, set
  `LAYA_THREADS` to the number of physical cores.
- **`LAYA_MODELS`:** the checkpoints to load. The first start downloads them from Hugging Face.
- **`LAYA_PORT`:** Laya defaults to 8000, which `mkdocs serve` and many dev servers also use.
  This page uses 8002.
- **`LAYA_API_KEY`:** optional. When set, requests need `Authorization: Bearer <key>`.

`curl -s localhost:8002/health` reports the device and the loaded checkpoints once it's ready.
See Laya's own docs for Docker images and the other settings.

## Point the client at it

The client always sends a key, so give it one. When `LAYA_API_KEY` is set, it must be the
same value.

=== "Plain Java"

    ```java
    TypeSafeClient client = TypeSafeClient.builder()
            .baseUrl("http://localhost:8002")
            .apiKey("local-test")
            .build();
    ```

    Or leave both out and export `TYPESAFE_BASE_URL=http://localhost:8002` and
    `TYPESAFE_API_KEY=local-test`. `builder().build()` falls back to them.

=== "Spring Boot"

    ```properties
    spring.ai.typesafe.base-url=http://localhost:8002
    spring.ai.typesafe.api-key=local-test
    ```

    The starter does not read `TYPESAFE_BASE_URL`. Set the property, or
    `SPRING_AI_TYPESAFE_BASE_URL`, for example in a `laya` profile.

## What differs

Measured against Laya 0.3.11:

| Area | Laya | Effect |
|---|---|---|
| `GET /v1/models` | not implemented, 404 | `listModels()` throws `TypeSafeNotFoundException` |
| `model` | honoured only for Laya's checkpoint names (`english`, `multilingual`, `typed-decisions`). Anything else, such as `jev-latest`, is auto-routed | `TypeSafeModels` constants have no effect. `response.model()` names Laya's model |
| Noul without instructions | rejected, 422 | Give every noul `instructions`, even when it has `whenTrue` / `whenFalse` criteria |
| Request id | not sent | `requestId()` is `null` |
| Output tokens | always `0` | `usage().outputTokens()` is `0` |
| Error body | `{"detail": "..."}`, no error type | `errorMessage()` is set, `errorType()` is `null` |
| Validation errors | a malformed question is 422, where Jev answers 400; a `null` state is accepted | Don't rely on the exact exception subtype |
| Extra answer fields | `action`, `routing` | Ignored by the client |
| Limits | 64 questions, 50,000 characters of state, 2 MB request | Larger requests fail with 413 |
| Concurrency | one forward pass at a time | [Batches](Batches.md) are served one request after another |

Everything else, including the noul, choice and score answers, probabilities, confidence,
score legends and the 401 on a wrong key, behaves as it does with Jev.

## How it compares

Running this project's [demos](../demos.md) against Laya's English checkpoint and against
Jev, on the same inputs:

| Workload | Laya | Jev |
|---|---|---|
| Plausibility (−125 °C or −255 °C in Paris) | rated 0.66–0.74, close to plausible | rated 0.05–0.53 |
| RAG: a passage contradicting the others | `INCLUDED` and ranked first | `CONFLICTING` |
| Tool search on four paraphrased requests | 2 of 4 | 4 of 4 |
| Cascade: clean invoices flagged for escalation | 2 of 3 | 0 of 3 |
| Guardrails: a self-harm message | flagged for review | routed to support |

The table shows one run. The tool search, cascade and judge results changed from run to
run.

In short:

- Laya's answers are less decisive: confidences sit nearer the middle.
- It misses subtler signals.
- It is sensitive to the order of fields in the state. The same record with its keys
  reordered can score quite differently, so build states in a fixed order (`LinkedHashMap`,
  not `Map.of`) when you compare runs.
- A warm three-question call takes about 30 ms on a laptop GPU.

Use Laya where speed, cost or data locality matter more than Jev's accuracy, and set
thresholds from measurements against Laya itself.

## Integration tests against Laya

The live ITs follow `TYPESAFE_BASE_URL`, so they run against Laya too:

```bash
TYPESAFE_BASE_URL=http://localhost:8002 TYPESAFE_API_KEY=local-test \
./mvnw -Pintegration-tests -pl typesafe-java-sdk,typesafe-spring-ai verify
```

Expect failures. Some ITs assert protocol details Laya lacks (model listing, request id,
output tokens, error types). Others assert answers tuned on Jev.

## See Also

- [TypeSafeClient](TypeSafeClient.md) — builder settings and environment variables
- [Spring Boot Starter](SpringBootStarter.md) — properties
- [Errors and Retries](ErrorsAndRetries.md) — the exception types above
