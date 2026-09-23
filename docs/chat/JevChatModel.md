# JevChatModel

A Jev **classifier** behind Spring AI's `ChatModel` interface, so a `ChatClient` can return
typed classifications: which team, how urgent, how severe.

!!! warning "Experimental"
    `JevChatModel` is a prototype, tracked in
    [issue #6](https://github.com/spring-ai-community/spring-ai-typesafe/issues/6). Its name
    and behaviour may change, and it is not auto-configured by the starter.

## A classifier, not a generator

A `ChatModel` normally takes messages and generates a message. Jev takes a state and answers
typed questions with numbers. `JevChatModel` keeps Jev what it is:

- **The questions are fixed when the model is built.** The prompt only supplies the state
  they are answered against. Nothing is inferred from prose.
- **The reply is the answers as JSON,** one field per question. `ChatClient.entity(...)`
  maps that straight onto a record.
- **What Jev cannot do is refused, not faked:**
  - streaming is unsupported
  - tools are never called
  - probabilities and confidence stay in metadata, not in the text

Use it where a fast, deterministic label, flag or level is what the caller needs: routing,
triage, moderation, intent detection, or any step in a workflow that only accepts a
`ChatModel`.

## Quick Start

```java
record Triage(String team, double urgent, double severity) {}

JevChatModel triage = JevChatModel.builder(typeSafeClient)
    .question("team", Choice.builder()
        .instructions("Which team should handle the user's ticket?")
        .option("infra", "Deploys, outages, servers and errors in production")
        .option("billing", "Invoices, payments, refunds and pricing")
        .option("support", "How-to questions and account help")
        .build())
    .question("urgent", Noul.builder()
        .instructions("Does the user's ticket need attention right now?")
        .whenTrue("Customers are affected now")
        .whenFalse("It can wait for normal working hours")
        .build())
    .question("severity", Score.of("How severe is the impact described in the ticket?",
        "Cosmetic", "Degraded for some users", "Full outage"))
    .build();

Triage t = ChatClient.create(triage)
    .prompt()
    .user("Production is down after the 14:00 deploy; every customer gets HTTP 500.")
    .call()
    .entity(Triage.class);
// e.g. Triage[team=infra, urgent=0.97, severity=1.8]
```

Name each question after the record component its answer should land in.

## What comes back

The reply is a JSON object with one field per question, in declaration order:

| Question | Field value | Example |
|---|---|---|
| `Noul` | its truth value, `0..1` | `"urgent": 0.97` |
| `Choice` | the selected label | `"team": "infra"` |
| `Score` | its value on the rubric, `0..` highest level | `"severity": 1.8` |

```java
String json = ChatClient.create(triage).prompt().user(ticket).call().content();
// {"team":"infra","urgent":0.97,"severity":1.8}
```

The full `SystemOneResponse`, with every probability and confidence, is in the generation
metadata. The response metadata carries the model, the token usage and the request id:

```java
ChatResponse response = ChatClient.create(triage).prompt().user(ticket).call().chatResponse();

SystemOneResponse jev = response.getResult().getMetadata().get(JevChatModel.RESPONSE_METADATA_KEY);
double infra = jev.choice("team").probabilityOf("infra");

response.getMetadata().getModel();                    // jev-1.13.0
response.getMetadata().getUsage().getPromptTokens();  // 210
```

## What Jev sees

By default, the prompt becomes this state:

```json
{
  "system": "Tickets come from enterprise customers.",
  "messages": [ { "role": "user", "content": "Production is down after ..." } ]
}
```

- `system` (`JevChatModel.SYSTEM_FIELD`) is left out when there is no system message.
- `messages` (`JevChatModel.MESSAGES_FIELD`) holds the user and assistant turns in order.
- When `ChatClient.entity(...)` appends JSON format instructions to the user message, they
  are removed. They tell a generator how to reply, and Jev's reply shape is fixed.

Write question instructions in terms of that state, for example "the user's ticket". To
judge a different shape, supply your own converter:

```java
JevChatModel.builder(typeSafeClient)
    .questions(questions)
    .stateConverter(prompt -> JsonContent.of(Map.of("ticket", prompt.getUserMessage().getText())))
    .build();
```

## Builder Configuration

| Builder method | Type | Default | Description |
|---|---|---|---|
| `question(String, Question)` | — | — (**at least one**) | A question every call answers. The name is the reply's field. |
| `questions(Map<String, Question>)` | — | — | Several questions at once, in map order. |
| `stateConverter(Function<Prompt, JsonContent>)` | — | `JevChatModel::defaultState` | How a prompt becomes the state. |
| `answerRenderer(Function<SystemOneResponse, String>)` | — | `JevChatModel::answersAsJson` | How the answers become the reply's text. |
| `observationRegistry(ObservationRegistry)` | `ObservationRegistry` | `NOOP` | Observe each call; see [observability](#observability). |
| `observationConvention(ChatModelObservationConvention)` | — | `DefaultChatModelObservationConvention` | Replace the observation's name and key values. |

The model named in the prompt's `ChatOptions` is used when set; otherwise the client's
default model.

## What it refuses

| Chat-model feature | `JevChatModel` |
|---|---|
| `stream(...)` | errors with `UnsupportedOperationException`. Jev answers every question at once. |
| Tools given to `ChatClient` | dropped. `getOptions()` are plain `ChatOptions`, so `ChatClient` never starts its tool loop. |
| A `Prompt` carrying tool callbacks directly | rejected with `IllegalArgumentException`. |
| Temperature, top-p, max tokens | ignored; Jev has no such settings. |

Advisors still run. Memory or retrieval advisors only add text to the state, though; they
don't make Jev generate anything.

## Observability

Each call is observed like any other Spring AI chat model: a `gen_ai.client.operation`
observation, contextually named `chat <model>`.

| Key | Value |
|---|---|
| `gen_ai.operation.name` | `chat` |
| `gen_ai.system` | `typesafe` (`JevChatModel.PROVIDER`) |
| `gen_ai.request.model` / `gen_ai.response.model` | e.g. `jev-latest` / `jev-1.13.0` |
| `gen_ai.response.id` | the request id |
| `gen_ai.usage.input_tokens` / `output_tokens` | the token counts Jev reports |

With Spring AI's `ChatModelMeterObservationHandler` registered, token usage is also recorded
as `gen_ai.client.token.usage`, tagged `gen_ai.token.type` `input`, `output` or `total`. The
SDK's retries happen inside the one observation. When the client is built from Spring Boot's
`RestClient.Builder`, each HTTP attempt is also recorded as `http.client.requests`.

```java
JevChatModel.builder(typeSafeClient)
    .questions(questions)
    .observationRegistry(observationRegistry)
    .build();
```

## With Spring Boot

Expose a `ChatClient` built on the model, **not** the model itself:

```java
@Bean
ChatClient triageClient(TypeSafeClient typeSafeClient, ObservationRegistry observationRegistry) {
    return ChatClient.create(JevChatModel.builder(typeSafeClient)
        .questions(TRIAGE_QUESTIONS)
        .observationRegistry(observationRegistry)
        .build());
}
```

!!! warning "Do not declare a `JevChatModel` bean"
    A `JevChatModel` is a `ChatModel`. Declared as a bean next to the application's real
    model, for example Anthropic's, it makes two `ChatModel` beans. Spring AI's
    auto-configured `ChatClient.Builder` injects a single `ChatModel`, so it then fails to
    start, or picks the wrong model if one is `@Primary`. Build the model inside the method
    that creates its `ChatClient`, as above, and inject that client by name.

The `TypeSafeClient` comes from the
[Spring Boot starter](../client/SpringBootStarter.md), already configured with the API key,
base URL and Boot's `RestClient.Builder`.

## When to use something else

- A verdict on a model's answer, with thresholds and feedback: [`JevJudge`](../judge/JevJudge.md).
- Judging and retrying inside a chat call: [`JevSelfRefineAdvisor`](../judge/JevSelfRefineAdvisor.md).
- Choosing tools: [`JevToolIndex`](../toolsearch/JevToolIndex.md).
- No `ChatClient` needed: call [`TypeSafeClient`](../client/TypeSafeClient.md) directly. It is
  the same request, without the adapter.

## See Also

- [The Three Primitives](../concepts/primitives.md)
- [Confidence](../concepts/confidence.md) — what the probabilities in the metadata mean
- [Issue #6](https://github.com/spring-ai-community/spring-ai-typesafe/issues/6) — open questions
