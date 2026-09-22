# Changes

## 0.2.0 (unreleased)

0.2.0 expands the [Model-as-a-Judge](judge/JevJudge.md) API: code checks alongside Jev
questions, a typed judge input, and a choice of what the self-refine advisor does when
judging itself fails. It has a few breaking changes, each listed below with its fix. Most
upgrades need a recompile and at most a type rename.

### At a glance

| Area | Change | Breaking? |
|---|---|---|
| `JevJudge` | [Code criteria](#code-criteria) via `check(...)` | no |
| `JevJudge` | [Typed input](#typed-judge-input) via `JevJudgeInput` and `judge(JevJudgeInput)` | no |
| `JevSelfRefineAdvisor` | [`judgeErrorPolicy`](#judge-error-policy-for-the-self-refine-advisor) | no |
| `JevCriterion` | [record → sealed interface](#jevcriterion-is-now-a-sealed-interface) | **source and binary** |
| `JevFinding` | [`answer()` may be `null`](#jevfindinganswer-may-be-null) | only if you add code checks |
| `JevSelfRefineAdvisor` | [Tool results moved to `tool_calls`](#tool-results-moved-to-tool_calls) | **behaviour** |
| `JevSelfRefineAdvisor` | [Judging failures no longer fail the chat call](#judging-failures-no-longer-fail-the-chat-call) | **behaviour** |
| `JevEvaluator` | [`supporting_context` is an array](#supporting_context-is-an-array) | **behaviour** |
| Examples | [`LlmJudgeDemoApplication` renamed](#demo-renamed-to-modeljudgedemoapplication) | examples only |

---

### New features

#### Code criteria

A judge can now hold checks answered by plain Java next to the questions it asks Jev. Use
them for anything the input already settles, such as whether a tool was called, whether the
answer parses, or whether it matches the expected output:

```java
JevJudge judge = JevJudge.builder(typeSafeClient)
    .check("matches_search_expectation",
           input -> !input.toolCalls().isEmpty()
                   == Boolean.TRUE.equals(input.field("search_required", Boolean.class)),
           "searched when no search was required, or skipped a required search")
    .noul("is_grounded", groundedNoul, 0.7d)
    .build();
```

Checks run locally before the call and never reach Jev. A failed check fails the verdict,
and its defect goes into the feedback. A judge still needs at least one question criterion.
See [Code criteria](judge/JevJudge.md#code-criteria).

#### Typed judge input

`JevJudgeInput` builds the judged state under fixed field names, the same wherever a judge
is used:

| Builder method | State field |
|---|---|
| `question(String)` | `user_question` |
| `answer(String)` | `assistant_answer` |
| `expected(Object)` | `expected_output` *(new)* |
| `context(...)` | `supporting_context` |
| `toolCall(...)` / `toolCalls(...)` | `tool_calls` *(new)* |
| `field(String, Object)` | any name you choose |

`judge(JevJudgeInput)` is the new primary entry point. `judge(String, String)` and
`judge(JsonContent)` are unchanged. `JevJudge.QUESTION_FIELD`, `JevJudge.ANSWER_FIELD` and
`JevEvaluator.CONTEXT_FIELD` remain as aliases of the new `JevJudgeInput` constants. See
[The state a judge builds](judge/JevJudge.md#the-state-a-judge-builds).

#### Judge error policy for the self-refine advisor

`JevSelfRefineAdvisor.Builder.judgeErrorPolicy(JudgeErrorPolicy)` chooses what happens when
the judging call itself fails:

- `FAIL_OPEN` *(default)*: log a warning and return the response unjudged.
- `FAIL_CLOSED`: rethrow the `TypeSafeException`.

See [When judging fails](judge/JevSelfRefineAdvisor.md#when-judging-fails).

---

### Breaking changes and how to migrate

#### `JevCriterion` is now a sealed interface

`JevCriterion` was a record. It is now a sealed interface with two records:
`JevCriterion.QuestionCriterion`, which is the old record, and `JevCriterion.CodeCriterion`.
The static factories `JevCriterion.noul(...)`, `score(...)` and `choice(...)` still exist,
but they now return `QuestionCriterion`.

| If your code… | It breaks because… | Do this |
|---|---|---|
| calls only `JevJudge.builder().noul/score/choice(...)` | — | nothing; **recompile** |
| calls `JevCriterion.noul/score/choice(...)` | the factories' return type changed | **recompile**. Code compiled against 0.1.0 fails at runtime with a `LinkageError`, such as `IncompatibleClassChangeError` |
| declares `JevCriterion c = JevCriterion.choice(...)` and reads `c.acceptedOptions()` | the interface only has `name()` | type the variable as `JevCriterion.QuestionCriterion` |
| reads `finding.criterion().question()`, `.minimum()` or `.acceptedOptions()` | same | pattern-match first (below) |
| calls `new JevCriterion(name, question, minimum, options)` | the record constructor is gone | use the factories or `new JevCriterion.QuestionCriterion(...)` |
| deconstructs `JevCriterion(var name, var question, ...)` in a pattern | same | deconstruct `JevCriterion.QuestionCriterion(...)` |

```java
// 0.1.0
double minimum = finding.criterion().minimum();

// 0.2.0
if (finding.criterion() instanceof JevCriterion.QuestionCriterion question) {
    double minimum = question.minimum();
}
```

To walk every criterion of a judge, filter by kind:

```java
judge.criteria().stream()
    .filter(JevCriterion.QuestionCriterion.class::isInstance)
    .map(JevCriterion.QuestionCriterion.class::cast)
    .forEach(question -> ...);
```

#### `JevFinding.answer()` may be `null`

A finding from a code check has no model answer, so `answer()` is `null`. Nothing changes
unless you add code checks. If you do, and you read `answer()`, guard it:

```java
if (finding.answer() instanceof NoulAnswer noul) { ... }   // false for null, no NPE
```

#### Tool results moved to `tool_calls`

`JevSelfRefineAdvisor` used to flatten tool results into `user_question` as
`<name>=<result>` lines. Now each assistant tool call is paired, in order, with its result (by id, or by
tool name when the provider leaves the id blank) and sent in its own `tool_calls` field as
`{name, arguments, result}`. `user_question` holds
only the system, user and assistant text.

**Affected:** criteria whose instructions expect tool output inside `user_question`, for
example "is every value supported by the question?".

**Migrate:** point those criteria at the new field:

```java
.noul("is_grounded", Noul.builder()
    .instructions("Is every value in `assistant_answer` supported by a result in `tool_calls`?")
    .whenFalse("States a value no tool returned")
    .build(), 0.7d)
```

A criterion that only asks whether a tool was called is better written as a
[code check](judge/JevJudge.md#code-criteria).

#### Judging failures no longer fail the chat call

In 0.1.0, any `TypeSafeException` from the judging call propagated out of the `ChatClient`
call. In 0.2.0 the default is `FAIL_OPEN`: the advisor logs a warning and returns the answer
unjudged.

**Affected:** applications that relied on an outage failing the call. That matters where an
unjudged answer must never reach a user.

**Migrate:** restore the 0.1.0 behaviour explicitly:

```java
JevSelfRefineAdvisor.builder()
    .judge(judge)
    .judgeErrorPolicy(JevSelfRefineAdvisor.JudgeErrorPolicy.FAIL_CLOSED)
    .build();
```

Exceptions thrown by a code check are not covered by the policy and always propagate.

#### `supporting_context` is an array

`JevEvaluator` used to send the retrieved documents as one string joined with line
separators. It now sends a JSON array with one entry per document. Documents without text
are skipped.

**Affected:** criteria that refer to `supporting_context` still see the same text, now split
per document. If a criterion sits close to its threshold, re-check it against a few real
cases. Code that inspects the request state directly is affected too, for example tests
using `jsonPath("$.state.supporting_context")`.

**Migrate:** assert on elements instead, e.g. `$.state.supporting_context[0]`. If you need
the joined string, call `judge.judge(JsonContent.of(...))` with the state you want.

`JevEvaluator` also no longer builds the context through the `Evaluator` default method
`doGetSupportingData`, so a subclass that overrode it to customise the context is now
bypassed. Prepare the documents before building the `EvaluationRequest` instead, or call
`JevJudge.judge(JevJudgeInput)` directly with the context you want.

#### Demo renamed to `ModelJudgeDemoApplication`

In the `examples` module, `LlmJudgeDemoApplication` is now `ModelJudgeDemoApplication`, to
match the Model-as-a-judge terminology. The examples module's default `start-class` follows
the rename. Update any `-Dspring-boot.run.main-class=...demo.judge.LlmJudgeDemoApplication`
you have scripted.

---

### Upgrade checklist

1. Bump to `0.2.0` and **recompile** everything that uses `org.springaicommunity.typesafe.judge`.
2. Fix any compile errors around `JevCriterion` accessors, as described
   [above](#jevcriterion-is-now-a-sealed-interface).
3. Using `JevSelfRefineAdvisor` with tools? Re-point groundedness criteria at `tool_calls`.
4. Need an outage to fail the chat call? Set `judgeErrorPolicy(FAIL_CLOSED)`.
5. Asserting on the judged state in tests? Update `supporting_context` to the array form.
