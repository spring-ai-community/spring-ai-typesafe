# TypeSafeClient

The entry point of the SDK. Wraps the HTTP transport with a default model, a retry policy
and typed exceptions, and stitches the `x-typesafe-request-id` header onto the response so
the id travels with the data it describes.

**Features:**

- Zero-configuration construction from environment variables
- Four `systemOne` overloads for text, object, array and `JsonContent` state
- A [batch API](Batches.md) for scoring many states concurrently
- Per-request API key resolution, so a rotated key takes effect without a rebuild
- [Typed exceptions and retries](ErrorsAndRetries.md)

## Quick Start

```java
TypeSafeClient client = TypeSafeClient.builder().build();

SystemOneResponse response = client.systemOne(
        "My card was charged twice.",
        Map.of("refund_requested", Noul.of("Is the customer asking for money back?")));
```

`builder().build()` with no arguments reads `TYPESAFE_API_KEY`, `TYPESAFE_BASE_URL` and
`TYPESAFE_DEFAULT_MODEL` from the environment — the same variables the official Python and
JavaScript SDKs read, named in `TypeSafeConstants`.

In a Spring Boot application you do not build it at all; the
[starter](SpringBootStarter.md) contributes the bean.

## Builder Configuration

```java
TypeSafeClient client = TypeSafeClient.builder()
        .apiKey(System.getenv(TypeSafeConstants.API_KEY_ENV))
        .baseUrl(TypeSafeConstants.DEFAULT_BASE_URL)
        .defaultModel(TypeSafeModels.JEV_LATEST)
        .timeout(Duration.ofSeconds(10))
        .retryPolicy(RetryPolicy.defaults())
        .build();
```

| Builder method | Type | Default | Description |
|---|---|---|---|
| `apiKey(String)` | `String` | `$TYPESAFE_API_KEY` | The API key. |
| `apiKey(Supplier<String>)` | `Supplier<String>` | — | Consulted **per request**, so a rotated key takes effect without rebuilding the client. |
| `baseUrl(String)` | `String` | `$TYPESAFE_BASE_URL`, else `https://api.typesafe.ai` | The API base URL. |
| `defaultModel(String)` | `String` | `$TYPESAFE_DEFAULT_MODEL`, else `jev-latest` | Applied to any request that does not name a model. |
| `timeout(Duration)` | `Duration` | `10s` | Per-attempt HTTP timeout. Also counted against the retry budget — see [Errors and Retries](ErrorsAndRetries.md). |
| `retryPolicy(RetryPolicy)` | `RetryPolicy` | `RetryPolicy.defaults()` | Which failures are repeated and how often. |
| `headers(HttpHeaders)` | `HttpHeaders` | empty | Extra headers on every request. **Adds** — call it twice and both sets are sent. |
| `restClientBuilder(RestClient.Builder)` | `RestClient.Builder` | — | Supply the transport yourself, keeping control of timeouts, interceptors and observability. |
| `typeSafeApi(TypeSafeApi)` | `TypeSafeApi` | — | Supply a fully assembled transport layer. |

!!! note "Headers the SDK owns"
    `Authorization`, `Content-Type` and `Accept` are set by the SDK after your headers are
    applied, so supplying your own value for any of the three replaces it rather than
    sending both.

## Asking questions

Four overloads differ only in the shape of the state:

```java
// Text
client.systemOne("Help! My payouts have been failing.", questions);

// A JSON object — name fields in your instructions to point a question at one of them
Map<String, Object> state = new LinkedHashMap<>();
state.put("sender", sender);
state.put("message", body);
client.systemOne(state, questions);

// A JSON array, for a thread or a list
client.systemOne(List.of("Hi", "My card was charged twice."), questions);

// The union type directly
client.systemOne(JsonContent.of(anything), questions);
```

A fifth takes a fully assembled request, which is how you override the model for one call:

```java
SystemOneRequest request = SystemOneRequest.builder()
        .state("A short sentence.")
        .model(TypeSafeModels.JEV_PREVIEW)
        .question("probe", Noul.of("Is this a sentence?"))
        .build();

client.systemOne(request);
```

The model is optional on a request. Leave it out and the client fills in its default,
which is what makes `TYPESAFE_DEFAULT_MODEL` and `spring.ai.typesafe.model` reach the wire
through this overload too.

### Key order matters

JSON object keys have no meaning, but the model reads the state, the questions and any
structured instructions in the order they are sent, and a different order can change an
answer. On hosted Jev the effect is small; on some compatible servers, such as
[Laya](Laya.md), it can flip a decision.

`Map.of` iterates in an order that changes with every JVM run, so the same code can send a
different request each time. Build multi-key maps in a fixed order instead:

- **Questions:** `SystemOneRequest.builder().question(name, q)`, or a `LinkedHashMap`.
- **State and structured instructions:** a `LinkedHashMap`, with the part the question is
  about first, such as the request before a catalogue, or the question before its hints.
- **Options and levels:** `Choice.builder().option(...)` and `Score.builder().level(...)`
  already keep declaration order.

The SDK's own components (the judge, the RAG post-processors, the tool index and
`JevChatModel`) build their requests in a fixed order.

## Reading answers

```java
double  urgency    = response.noulValue("is_urgent");
String  department = response.choiceValue("department");
double  score      = response.scoreValue("frustration");

// The typed answer, for everything beyond the value
ChoiceAnswer choice = response.choice("department");
choice.confidence();
choice.probabilityOf("billing");
choice.optionsAbove(0.1d);      // labels, descending by probability

// Grouped views, in the order the request named the questions
Map<String, NoulAnswer>   nouls   = response.nouls();
Map<String, ChoiceAnswer> choices = response.choices();
Map<String, ScoreAnswer>  scores  = response.scores();

response.usage().inputTokens();
response.requestId();           // quote this when reporting a problem
```

Asking for the wrong kind raises `TypeSafeAnswerTypeException`, and asking for a name the
response does not carry raises `TypeSafeMissingAnswerException` — both client-side, both
telling you which name and what was actually there.

## Listing models

```java
for (ModelMetadata model : client.listModels()) {
    System.out.printf("%s — %s (%s)%n", model.name(), model.description(), model.releaseDate());
}
```

`TypeSafeModels` names the known ids: `JEV_LATEST`, `JEV_PREVIEW` and the pinned
`JEV_1_13_0`. Aliases resolve server-side, and the response reports the version it resolved
to — so asking for `jev-latest` comes back as `jev-1.13.0`.

## Which Jackson mapper serializes your request

The SDK never serializes the request body itself — the `RestClient`'s message converters do,
and which mapper they carry depends on how the client was built:

| How the client is built | Mapper |
|---|---|
| `TypeSafeClient.builder()` with no `restClientBuilder` | a fresh `JsonMapper` carrying only ServiceLoader-discovered modules |
| the [Spring Boot starter](SpringBootStarter.md) | the application's `JsonMapper` bean, with `spring.jackson.*` and every module bean applied |
| your own `restClientBuilder(...)` or `typeSafeApi(...)` | whatever converters you supplied |

This only matters when a value depends on Jackson configuration. `String`, `Map`, `List`,
records, enums and `java.time` serialize identically either way; a POJO relying on a custom
serializer bean or a `spring.jackson.*` setting will serialize differently in the plain-SDK
path. If that matters, pass your own `RestClient.Builder` so both paths share one mapper.

## Thread safety

A `TypeSafeClient` is immutable and safe to share. The [batch API](Batches.md) calls it from
several threads by design.

## See Also

- [Batches](Batches.md) — scoring many states concurrently
- [Errors and Retries](ErrorsAndRetries.md)
- [Spring Boot Starter](SpringBootStarter.md)
- [The Three Primitives](../concepts/primitives.md)
