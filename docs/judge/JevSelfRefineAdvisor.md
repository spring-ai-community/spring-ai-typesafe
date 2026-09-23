# JevSelfRefineAdvisor

A self-refine `CallAdvisor`: it judges every model response with [`JevJudge`](JevJudge.md)
and, when the response falls short, feeds the defect back into the prompt and tries again.

## How it works

```mermaid
flowchart LR
    A[Request] --> B[Model]
    B --> C{JevJudge}
    C -- passed --> D[Return response]
    C -- failed --> E[Append defect to prompt]
    E --> B
    C -- attempts exhausted --> F[Return last response<br/>or throw]
```

The retry prompt is rebuilt from the **original** request each time rather than from the
previous attempt, so feedback does not compound across attempts.

## Quick Start

```java
ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultTools(new WeatherTools())
    .defaultAdvisors(JevSelfRefineAdvisor.builder()
            .judge(judge)
            .maxRepeatAttempts(3)
            .build())
    .build();

String answer = chatClient.prompt("What is the weather in Paris?").call().content();
```

## Builder Configuration

| Builder method | Type | Default | Description |
|---|---|---|---|
| `judge(JevJudge)` | `JevJudge` | — (**required**) | What to evaluate each response against. |
| `maxRepeatAttempts(int)` | `int` | `3` | Retries after the first attempt. Capped at `MAX_REPEAT_ATTEMPTS_LIMIT` (100). |
| `failOnExhaustedAttempts(boolean)` | `boolean` | `false` | Throw `JevSelfRefineFailedException` instead of returning the best attempt. |
| `skipEvaluationPredicate(BiPredicate<ChatClientRequest, ChatClientResponse>)` | — | skips when the response has tool calls | A tool call is not an answer yet, so there is nothing to judge. |
| `judgeErrorPolicy(JudgeErrorPolicy)` | `JudgeErrorPolicy` | `FAIL_OPEN` | What to do when the judging call itself fails. See [when judging fails](#when-judging-fails). |
| `order(int)` | `int` | `DEFAULT_ORDER` (`HIGHEST_PRECEDENCE + 250`) | Where in the advisor chain this runs. See [where it sits](#where-it-sits-and-why). |

!!! note "Retry until it passes is not supported"
    `maxRepeatAttempts` is capped deliberately. Each attempt is a model call *plus* a
    judging call, and a criterion the model cannot satisfy would otherwise loop forever.

## What the judge can see

The advisor builds a [`JevJudgeInput`](JevJudge.md#the-state-a-judge-builds) from the
original request and the answer it produced:

| Field | Carries |
|---|---|
| `user_question` | the system message and the user and assistant turns, each prefixed with its role |
| `assistant_answer` | the final answer |
| `tool_calls` | every tool call in the prompt, paired in order with its result: `{name, arguments, result}` |

A `ToolResponseMessage` carries no text of its own, so its results are unpacked into
`tool_calls` rather than dropped. Each result goes to the earliest open call with the same
id, or with the same tool name when the provider leaves ids blank (as Google GenAI does), so
parallel calls and ids reused across turns stay apart. Write a groundedness criterion
against that field:

```java
.noul("is_grounded", Noul.builder()
    .instructions("Is every value in `assistant_answer` supported by a result in `tool_calls`?")
    .whenFalse("States a value no tool returned")
    .build(), 0.7d)
```

Whether a tool was called at all is better settled by a
[code check](JevJudge.md#code-criteria) than asked of Jev.

## Where it sits, and why

`ChatClient` registers chat memory at `HIGHEST_PRECEDENCE + 200` and runs the tool loop in a
`ToolCallingAdvisor` at `HIGHEST_PRECEDENCE + 300`. The advisor's default,
`DEFAULT_ORDER = HIGHEST_PRECEDENCE + 250`, sits between the two:

| Placement | Why |
|---|---|
| **after chat memory** | memory records only the answer finally returned, not every rejected attempt |
| **before the tool loop** | a retry re-runs the tools, so a tool that returned something wrong can return something else |

From there, the attempt's tool traffic never appears in the prompt this advisor sees. So it
**records** it: for each attempt, the request's tool callbacks are wrapped, and every call
lands in `tool_calls` with its arguments and result. Each attempt is judged against its own
tool calls.

!!! note "Recorded tools"
    Tools passed as callbacks, through `ChatClient.tools(...)` or `defaultTools(...)`, are
    recorded. A tool resolved by name through a `ToolCallbackResolver` is not.

Ordered *after* the tool loop instead, for example with `.order(BaseAdvisor.LOWEST_PRECEDENCE
- 2000)` (the 0.1.0 default), the advisor reads tool calls from the prompt. A retry then
re-asks the model with the same tool history and does **not** re-run the tools: in live runs
the model never recovered from a bad tool result that way. Placed before chat memory
(below `+200`), every rejected attempt is written to memory.

## Failing hard

By default the advisor returns the **best attempt** once attempts run out: the one with the
fewest failed criteria, the later one on a tie. A later attempt is not necessarily a better
one, so the last is not returned just for being last. Turn that around where shipping a rejected answer is worse than
failing:

```java
JevSelfRefineAdvisor.builder()
    .judge(judge)
    .maxRepeatAttempts(2)
    .failOnExhaustedAttempts(true)   // throws JevSelfRefineFailedException
    .build();
```

```java
catch (JevSelfRefineFailedException ex) {
    log.error("gave up: {}", ex.verdict().summary());
    ex.verdict().failures().forEach(f -> log.error("  {}", f.detail()));
}
```

## When judging fails

A TypeSafe outage, timeout or error response says nothing about the answer. By default the
advisor **fails open**: it logs a warning and returns the response it could not judge,
rather than failing a chat call whose answer may be fine. That matches its best-effort
stance on exhausted attempts.

Where an unjudged answer must never ship, fail closed instead. The `TypeSafeException` is
then rethrown:

```java
JevSelfRefineAdvisor.builder()
    .judge(judge)
    .judgeErrorPolicy(JevSelfRefineAdvisor.JudgeErrorPolicy.FAIL_CLOSED)
    .build();
```

Only failures of the judging call are covered. An exception thrown by one of the judge's
code checks is a bug in the check, and always propagates.

## Ordering with the guardrail advisor

The two compose, and they do different jobs.
[`JevGuardrailAdvisor`](../guardrails/JevGuardrailAdvisor.md) defaults to
`LOWEST_PRECEDENCE - 1000`, which places it *inside* the self-refine loop, nearer the model.
It screens every attempt, so an unsafe draft is replaced by the refusal before it is judged.
See [where the guardrail sits](../guardrails/JevGuardrailAdvisor.md#where-it-sits) for the
alternative of screening once per turn.

```java
.defaultAdvisors(
    JevSelfRefineAdvisor.builder().judge(judge).build(),          // quality, retries
    JevGuardrailAdvisor.builder(typeSafeClient).build())          // safety, every attempt
```

Retrying does not help a guardrail: an unsafe answer is not a draft.

## Streaming

`adviseStream` is unsupported and returns `Flux.error(UnsupportedOperationException)`. A
verdict needs the whole answer, so there is nothing useful to emit incrementally.

## See Also

- [JevJudge](JevJudge.md) — the criteria this advisor evaluates
- [JevGuardrailAdvisor](../guardrails/JevGuardrailAdvisor.md)
- [Demos](../demos.md) — `ModelJudgeDemoApplication`
