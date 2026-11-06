# Using Drex

[Drex](https://console.nace.ai/docs/drex) is nace.ai's family of hosted decision models. They
serve the same `POST /v1/systemone` wire protocol as Jev, at `https://console.nace.ai`, so
`TypeSafeClient` can target Drex with a different base URL, key and model name.
`drex-max`, `drex-max-v1.1` and `drex-max-finance` also read images; this SDK sends text
only, see [what differs](#what-differs).

!!! warning "Requires SDK fixes after 0.4.0"
    The score-legend and `TypeSafeErrorDetail` fixes ship in the first release after
    `0.4.0` (the POM currently uses `0.5.0-SNAPSHOT`). On `0.4.0`, a `drex-max` response
    containing any score answer fails as a whole
    with `MismatchedInputException`, because `ScoreAnswer` rejects the array-shaped
    `legend`. Drex's nested `error` envelope also loses its `message` and validation
    `issues` on `0.4.0`.

    Until that release, use a SNAPSHOT or a build from `main` containing both fixes.
    For SNAPSHOT dependencies, enable snapshots for the Central Portal repository at
    `https://central.sonatype.com/repository/maven-snapshots/`, as configured in the
    project's [POM](https://github.com/spring-ai-community/spring-ai-typesafe/blob/main/pom.xml).

!!! warning "A different model, not a different endpoint"
    The protocol matches closely; the model is another one. The thresholds in this project's
    examples and tests were tuned on Jev, so check your criteria against the Drex model you
    pick before relying on its decisions. Drex also rejects some requests Jev accepts; see
    [what differs](#what-differs).

## Pick a model

Create a key on the [console](https://console.nace.ai) and keep it in `NACE_API_KEY`, the
variable the official docs use. Then name the model: Drex has no `jev-latest`, and
`drex-latest` points to `drex-v2.0`, not to `drex-max`.

| Model | Reads | Questions per request | Options per choice, levels per score |
|---|---|---|---|
| `drex-max` | text and images | 512 | 255 |
| `drex-max-v1.1` | text and images | 512 | 255 |
| `drex-max-finance` | text and images | 512 | 255 |
| `drex-v2.0`, also `drex-latest` | text | 512 | 255 |
| `drex-v1.5` | text | 512 | 255 |

This table is non-exhaustive; see the official [model catalog](https://console.nace.ai/docs/drex/reference/models).
The live `GET /v1/models` catalog on 2026-11-06 listed all three Max models as multimodal.
It reports a 262,144-token input limit per question for `drex-max`, and across the whole
request for `drex-max-v1.1` and `drex-max-finance` (including images and all questions).
Pricing is per input token; output tokens are not billed. See the official
[models](https://console.nace.ai/docs/drex/reference/models),
[limits](https://console.nace.ai/docs/drex/reference/limits) and
[pricing](https://console.nace.ai/docs/drex/reference/pricing) pages, and the
[Drex Max guide](https://console.nace.ai/docs/drex/guides/drex-max).

## Point the client at it

The client appends `/v1/systemone` and `/v1/models` to the base URL, so give it the host
only.

=== "Spring Boot"

    ```properties
    spring.ai.typesafe.base-url=https://console.nace.ai
    spring.ai.typesafe.api-key=${NACE_API_KEY}
    spring.ai.typesafe.model=drex-max
    spring.ai.typesafe.timeout=60s
    spring.ai.typesafe.retry.total-timeout=150s
    ```

    Drex gives the model up to 55 s on a heavy request, so the SDK's default 10 s per-attempt
    timeout can cut a large one short. The retry budget must stay above the timeout, or no
    retry can ever fit in it.

=== "Plain Java"

    ```java
    TypeSafeClient client = TypeSafeClient.builder()
            .baseUrl("https://console.nace.ai")
            .apiKey(System.getenv("NACE_API_KEY"))
            .defaultModel("drex-max")
            .timeout(Duration.ofSeconds(60))
            .retryPolicy(RetryPolicy.builder()
                    .totalTimeout(Duration.ofSeconds(150))
                    .build())
            .build();
    ```

    `RetryPolicy` keeps a 30 s budget by default, which a 60 s timeout already exceeds, so
    without the explicit policy no retry ever fits. The Spring Boot property above sets the
    same 150 s budget.

    Or leave all three out and export `TYPESAFE_BASE_URL=https://console.nace.ai`,
    `TYPESAFE_API_KEY` and `TYPESAFE_DEFAULT_MODEL=drex-max`, which is how the official
    [migration guide](https://console.nace.ai/docs/drex/guides/migrate-from-typesafe) points
    the TypeSafe SDKs at Drex.

## What differs

Verified against `https://console.nace.ai` with `drex-max` on 2026-10-26; structured
instruction component calls and the model catalog were rechecked on 2026-11-06 as detailed
below. API rules also come from the official [questions](https://console.nace.ai/docs/drex/guides/questions),
[errors](https://console.nace.ai/docs/drex/reference/errors) and
[limits](https://console.nace.ai/docs/drex/reference/limits) pages:

| Area | Drex | Effect |
|---|---|---|
| `model` | must be a `drex-*` name; `jev-latest` is 422, with a message that lists the accepted names | Set `spring.ai.typesafe.model` or `defaultModel(...)`. The `TypeSafeModels` constants are rejected. |
| `instructions` | accepts strings, objects and arrays; structured JSON is rendered to text | Components can send structured instructions; see [below](#components-that-send-structured-instructions). |
| Criteria descriptions | a choice option or a noul `true` / `false` criterion must be a string or `null`; a score level must be a string | Use the `String` overloads of `option(...)`, `whenTrue(...)`, `whenFalse(...)` and `level(...)`, not the `Map` or `List` ones. |
| Score `legend` | an array of labels in level order, where Jev sends an object keyed by level | Read into the same `legend()` map, so `labelOf(level)` and `nearestLabel()` work as on Jev. |
| `GET /v1/models` | `{"models": [...]}` with `name`, `description`, `release_date` and `alias_for` | `listModels()` works. |
| Request id | sent as `x-typesafe-request-id`, and as `x-request-id` and `request_id` in the body | `requestId()` is set, on errors too. |
| Error body | `{"error": {"type": "...", "message": "...", "issues": [{"path": "...", "message": "..."}]}}` for every status | `errorType()` and `errorMessage()` are set. A 422's `issues` are the `validationErrors()`, with `path()` as Drex writes it and `type()` `null`. |
| Statuses | 402 (`insufficient_credit`, `payment_required`) is new; 529 `overloaded` carries `retry-after` and `retry-after-ms`; every validation failure is 422 | 402 is a plain `TypeSafeApiException` and is not retried. 529 is `TypeSafeOverloadedException`, retried within the budget on the computed backoff; this SDK honours a server delay (`retry-after-ms`) only for `TypeSafeRateLimitException`, so the 529's `retry-after` is not read. Don't rely on `TypeSafeBadRequestException` for a malformed request. |
| Limits | 512 questions per request and 255 options or levels per question on every model; 4,194,304-byte body; `drex-max` 262,144 input tokens per question; `drex-max-v1.1` and `drex-max-finance` 262,144 tokens across the whole request, including images and all questions; `drex-v2.0` 131,072-token state and 262,144-token row; `drex-v1.5` 131,072-token state and 139,264-token row | Over a limit is 422 with a message that names it. A `JevToolIndex` choice has one option per candidate, so more than 255 candidates in one search must be split across searches. |
| Timeout | the model is given up to 55 s under load | Set `timeout` to 60 s and keep the retry budget above it. |
| Rate limits | per account: 120 requests per minute and 8 in flight on the free tier, 600 and 16 when paid; 429 carries `retry-after-ms` | `JevBatchOptions.defaults()` runs four requests at a time, within either limit. |
| Images | `drex-max`, `drex-max-v1.1` and `drex-max-finance` read up to 8 images per request from a top-level `images` array, per the [Drex Max guide](https://console.nace.ai/docs/drex/guides/drex-max#images); other active models are text-only | `SystemOneRequest` has no such field, so this SDK sends text only. |

Everything else, including the noul, choice and score answers, probabilities, confidence
and token usage, has the same shape as Jev's.

## Components that send structured instructions

Some of this project's components write `instructions` as a JSON object, to separate the
question from its focus. Drex accepts object and array instructions and renders them to
text, as described in the official [questions guide](https://console.nace.ai/docs/drex/guides/questions).
Criteria descriptions still have the string restrictions listed above.

The following checks use this repository's component code with its default object
instructions, against `drex-max` on 2026-11-06. Each verified component made one live
request (HTTP 200); these small examples check compatibility, not decision quality.

| Component | Verification against Drex |
|---|---|
| `JevDocumentFilter` | Verified: one passage received classification metadata. |
| `JevDocumentReranker` | Verified: one passage received a relevance score. |
| `JevToolIndex` | Verified: a search over two candidates ranked the weather tool first. |
| `JevJudge`, `JevEvaluator`, `JevSelfRefineAdvisor`, `JevGuardrailAdvisor`, `JevChatModel` | Send string instructions and the descriptions from your criteria, which Drex accepts; not run end to end in this check. |

## Integration tests against Drex

The live ITs follow `TYPESAFE_BASE_URL`, but they pin `TypeSafeModels.JEV_LATEST` as the
model, which Drex rejects. So they don't run against Drex as they are.

## See Also

- [Using Ollama](Ollama.md) and [Using Laya](Laya.md): local System One servers
- [TypeSafeClient](TypeSafeClient.md): builder settings and environment variables
- [Spring Boot Starter](SpringBootStarter.md): properties
- [Errors and Retries](ErrorsAndRetries.md): the exception types above
- [Drex documentation](https://console.nace.ai/docs/drex), and its
  [Migrate from TypeSafe](https://console.nace.ai/docs/drex/guides/migrate-from-typesafe) guide
