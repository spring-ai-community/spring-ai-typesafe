# Changes

## 0.2.0 (unreleased)

0.2.0 expands the [Model-as-a-Judge](judge/JevJudge.md) API: code checks alongside Jev
questions, a typed judge input, criteria that depend on other answers, outcomes that say
*why* a criterion was not decided, and confidence gating on the verdict rather than the
distribution. The self-refine advisor now re-runs tools on a retry while still showing the
judge every tool call, returns its best attempt, and lets you choose what happens when
judging itself fails. It has a few breaking changes, each listed below with its fix. Most
upgrades need a recompile and at most a type rename.

### At a glance

| Area | Change | Breaking? |
|---|---|---|
| `JevJudge` | [Code criteria](#code-criteria) via `check(...)` | no |
| `JevJudge` | [Typed input](#typed-judge-input) via `JevJudgeInput` and `judge(JevJudgeInput)` | no |
| `JevSelfRefineAdvisor` | [`judgeErrorPolicy`](#judge-error-policy-for-the-self-refine-advisor) | no |
| `JevJudge` | [`appliesWhen`, `failOnError`, `failFast`](#conditional-criteria-error-policy-and-fail-fast) | no |
| `JevJudge` | [criteria that depend on other answers: `whenChosen`, `whenPassed`](#criteria-that-depend-on-other-answers) | no |
| `JevSelfRefineAdvisor` | [records tool calls; default order `LOWEST_PRECEDENCE - 2000` → `HIGHEST_PRECEDENCE + 250`](#the-self-refine-advisor-moved-before-the-tool-loop) | **behaviour** |
| `JevSelfRefineAdvisor` | [returns the best attempt, not the last](#the-best-attempt-is-returned-not-the-last) | **behaviour** |
| `JevFinding.Outcome` | [new `ERROR` and `NOT_APPLICABLE` values](#outcome-has-two-new-values) | **source** (exhaustive `switch`) |
| `JevJudge` | [a missing answer is `ERROR`, not `INCONCLUSIVE`](#a-missing-answer-is-error-not-inconclusive) | **behaviour** |
| `JevJudge` | [`minConfidence` gates on verdict support; default 0.5 → 0.6](#minconfidence-now-gates-on-how-much-probability-supports-the-verdict) | **behaviour** |
| `JevVerdict` | [`response()` may be `null`](#jevverdictresponse-may-be-null) | only with `appliesWhen` or `failFast` |
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

#### Conditional criteria, error policy and fail-fast

- **`appliesWhen`**: `JevCriterion.noul/score/choice(...).appliesWhen(predicate)` makes a
  question conditional. When the predicate is false, the question is not sent and its
  finding is `NOT_APPLICABLE`. It neither passes nor fails.
- **`failOnError(true)`**: a criterion the service could not answer (`ERROR`) blocks the
  verdict. Off by default.
- **`failFast(true)`**: when a code check fails, the Jev call is skipped and the questions
  are `NOT_APPLICABLE`. The verdict already fails, so the call would be wasted.

See [Reading the verdict](judge/JevJudge.md#reading-the-verdict).

#### Criteria that depend on other answers

`JevCriterion.noul/score/choice(...).whenChosen("mode", "answered")` applies a question only
when a choice selected one of the given labels. `whenPassed("name")` applies it only when
another criterion passed. The question is still asked in the same call, so the branch costs
nothing. When the dependency isn't met, the finding is `NOT_APPLICABLE`. See
[Criteria that depend on other answers](judge/JevJudge.md#criteria-that-depend-on-other-answers).

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

The same applies to criteria that were not asked (`NOT_APPLICABLE`).

#### `Outcome` has two new values

`JevFinding.Outcome` gains `ERROR`, when the service could not answer a criterion, and
`NOT_APPLICABLE`, when a criterion was not asked.

**Affected:** an exhaustive `switch` over `Outcome` no longer compiles.

**Migrate:** add the two cases. Neither blocks the verdict by default, so treat them like
`INCONCLUSIVE` if you only care about pass/fail.

#### A missing answer is `ERROR`, not `INCONCLUSIVE`

In 0.1.0, a criterion the service returned no answer for, or one with an answer kind the SDK
could not read, was reported `INCONCLUSIVE`, the same as an ambiguous question. It is now
`ERROR`: an instrument failure, not a verdict on the answer. `failOnInconclusive(true)` no
longer makes it block.

**Migrate:** if missing answers must block, add `failOnError(true)`. Code reading
`verdict.inconclusive()` to find outages should read `verdict.errors()`.

#### `minConfidence` now gates on how much probability supports the verdict

In 0.1.0, a score or choice was `INCONCLUSIVE` when the answer's own `confidence` was below
`minConfidence` (default 0.5). `confidence` measures how peaked the whole distribution is, so
an answer split between two *passing* levels was reported undecided, even though the pass was
never in doubt. In live runs, a 4-level `helpfulness` rubric was `INCONCLUSIVE` in 8 of 9
judgements this way.

`minConfidence` now applies to the probability on the verdict's side of the threshold. For a
score, that's the mass on the levels at or above `minimum` when it passes, below when it
fails. For a choice, it's the mass on the accepted options, or the rejected ones. The default
is now **0.6**, a clear majority; a coin flip between pass and fail supports the verdict at
0.5.

**Affected:** judges will report fewer `INCONCLUSIVE` scores and choices. An explicit
`minConfidence(0.5d)` from 0.1.0 is now permissive: 0.5 is the coin-flip point.

**Migrate:** remove an explicit `minConfidence(0.5d)` to get the new default, or raise it for
consequential decisions. Findings report the supporting probability in their detail, e.g.
`0.50 of the probability supports the verdict, needs at least 0.60`.

#### `JevVerdict.response()` may be `null`

When no question is asked, because every question criterion was not applicable or
`failFast` skipped the call, there is no `SystemOneResponse`, and `response()` is `null`.
Nothing changes unless you use `appliesWhen` or `failFast`. If you do, guard reads of
`response()`.

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

#### The self-refine advisor moved before the tool loop

`JevSelfRefineAdvisor`'s default order changed from `LOWEST_PRECEDENCE - 2000` to
`DEFAULT_ORDER = HIGHEST_PRECEDENCE + 250`. That's after chat memory (`+200`) and before
Spring AI's tool loop (`+300`). From there:

- **A retry re-runs the tools.** Ordered after the tool loop, a retry only re-asked the model
  with the same tool history, and in live runs the model never recovered from a bad tool
  result.
- **The judge still sees `tool_calls`.** The advisor wraps the request's tool callbacks for
  each attempt and records every call and result. Tools resolved by name through a
  `ToolCallbackResolver` are not recorded.
- **Rejected attempts no longer reach chat memory**, which now sits outside the retry loop.

**Affected:** applications that relied on the advisor running inside the tool loop, or on its
position relative to their own advisors. [`JevGuardrailAdvisor`](guardrails/JevGuardrailAdvisor.md)
is unaffected: at `LOWEST_PRECEDENCE - 1000` it was already inside the self-refine loop.

**Migrate:** restore the 0.1.0 placement with
`.order(BaseAdvisor.LOWEST_PRECEDENCE - 2000)`. The judge then reads tool calls from the
prompt, and a retry does not re-run the tools.

#### The best attempt is returned, not the last

Once `maxRepeatAttempts` is exhausted, the advisor used to return the last attempt. It now
returns the attempt with the fewest failed criteria, the later one on a tie.
`JevSelfRefineFailedException.verdict()` carries that attempt's verdict.

**Affected:** only callers who relied on getting the final attempt when every attempt fails.

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
6. `switch` over `JevFinding.Outcome`? Add `ERROR` and `NOT_APPLICABLE`.
7. Relying on `INCONCLUSIVE` for missing answers, or on `minConfidence(0.5d)`? See
   [the confidence change](#minconfidence-now-gates-on-how-much-probability-supports-the-verdict)
   and set `failOnError(true)` where outages must block.
8. Ordered other advisors around `JevSelfRefineAdvisor`'s old default? See
   [the new default order](#the-self-refine-advisor-moved-before-the-tool-loop).
