# Changes

## 0.2.0 (unreleased)

0.2.0 expands the [Model-as-a-judge](judge/JevJudge.md) API: code checks alongside Jev
questions, a typed judge input, criteria that depend on other answers, outcomes that say
*why* a criterion was not decided, and pass/fail and confidence decided from where the
probability lies. The self-refine advisor returns its best attempt, can re-run tools on a
retry while still showing the judge every tool call, and fails open on outages rather than
failing the chat call. It has a few breaking changes, each listed below with its fix. Most
upgrades need a recompile and at most a type rename.

### At a glance

| Area | Change | Breaking? |
|---|---|---|
| `JevJudge` | [Code criteria](#code-criteria) via `check(...)` | no |
| `JevJudge` | [Typed input](#typed-judge-input) via `JevJudgeInput` and `judge(JevJudgeInput)` | no |
| `JevJudge` | [`appliesWhen`, `failOnError`, `failFast`](#conditional-criteria-error-policy-and-fail-fast) | no |
| `JevJudge` | [Criteria that depend on other answers](#criteria-that-depend-on-other-answers): `whenChosen`, `whenPassed` | no |
| `JevSelfRefineAdvisor` | [`judgeErrorPolicy`](#judge-error-policy-for-the-self-refine-advisor) | no |
| `JevSelfRefineAdvisor` | [Re-running tools on a retry](#re-running-tools-on-a-retry) at `BEFORE_TOOLS_ORDER` | no |
| `JevCriterion` | [Record → sealed interface](#jevcriterion-is-now-a-sealed-interface); criteria validated when built | **source and binary** |
| `JevFinding` | [`answer()` may be `null`](#jevfindinganswer-may-be-null) | only with code checks or conditional criteria |
| `JevFinding.Outcome` | [New `ERROR` and `NOT_APPLICABLE` values](#outcome-has-two-new-values) | **source** (exhaustive `switch`) |
| `JevJudge` | [A missing answer is `ERROR`, not `INCONCLUSIVE`](#a-missing-answer-is-error-not-inconclusive) | **behaviour** |
| `JevJudge` | [Pass/fail and confidence from the probability split; `minConfidence` 0.5 → 0.6](#passfail-and-confidence-come-from-where-the-probability-lies) | **behaviour** |
| `JevVerdict` | [`response()` may be `null`](#jevverdictresponse-may-be-null) | only with `appliesWhen` or `failFast` |
| `JevSelfRefineAdvisor` | [Tool results moved to `tool_calls`](#tool-results-moved-to-tool_calls) | **behaviour** |
| `JevSelfRefineAdvisor` | [Outages no longer fail the chat call](#outages-no-longer-fail-the-chat-call) | **behaviour** |
| `JevSelfRefineAdvisor` | [The best attempt is returned, not the last](#the-best-attempt-is-returned-not-the-last) | **behaviour** |
| `JevEvaluator` | [`supporting_context` is an array](#supporting_context-is-an-array) | **behaviour** |
| Examples | [`LlmJudgeDemoApplication` renamed](#demo-renamed-to-modeljudgedemoapplication) | examples only |

`JevSelfRefineAdvisor`'s default order is unchanged: `LOWEST_PRECEDENCE - 2000`.

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

#### Conditional criteria, error policy and fail-fast

- **`appliesWhen`**: `JevCriterion.noul/score/choice(...).appliesWhen(predicate)` makes a
  question conditional on the input. When the predicate is false, the question is not sent
  and its finding is `NOT_APPLICABLE`. It neither passes nor fails.
- **`failOnError(true)`**: a criterion the service could not answer blocks the verdict. It is
  then reported as `FAILED`, in `failures()` and the feedback. Off by default.
- **`failFast(true)`**: when a code check fails, the Jev call is skipped and the questions
  are `NOT_APPLICABLE`. The verdict already fails, so the call would be wasted.

See [Reading the verdict](judge/JevJudge.md#reading-the-verdict).

#### Criteria that depend on other answers

`JevCriterion.noul/score/choice(...).whenChosen("mode", "answered")` applies a question only
when a choice selected one of the given labels. `whenPassed("name")` applies it only when
another criterion passed. The question is still asked in the same call, so the branch costs
nothing. When the dependency isn't met, or wasn't decided, the finding is `NOT_APPLICABLE`
and its answer is set aside. A criterion depends on at most one other, declared before it.
See [Criteria that depend on other answers](judge/JevJudge.md#criteria-that-depend-on-other-answers).

#### Judge error policy for the self-refine advisor

`JevSelfRefineAdvisor.Builder.judgeErrorPolicy(JudgeErrorPolicy)` chooses what happens when
the judging call itself fails:

- `FAIL_OPEN` *(default)*: on a transient failure (a connection error or timeout, a 408,
  429 or 5xx), log a warning and return the response unjudged. A client error, such as a bad
  API key or an invalid question, is still rethrown.
- `FAIL_CLOSED`: rethrow every `TypeSafeException`.

See [When judging fails](judge/JevSelfRefineAdvisor.md#when-judging-fails).

#### Re-running tools on a retry

At its default order the advisor runs inside Spring AI's tool loop. A retry re-asks the model
with the tool history it already has, and does not call the tools again. Ordered at the new
`JevSelfRefineAdvisor.BEFORE_TOOLS_ORDER` (`HIGHEST_PRECEDENCE + 250`, between chat memory
and the tool loop), a retry re-runs the tools. The advisor then records each attempt's tool
calls from the request's tool callbacks, so the judge still sees them in `tool_calls`.

That position is also outside retrieval and other advisors at order `0`, so retrieval
re-runs on each attempt and the judge does not see the retrieved context. See
[Where it sits, and why](judge/JevSelfRefineAdvisor.md#where-it-sits-and-why).

---

### Breaking changes and how to migrate

#### `JevCriterion` is now a sealed interface

`JevCriterion` was a record. It is now a sealed interface with two records:
`JevCriterion.QuestionCriterion`, which replaces the old record and adds two components
(`appliesWhen`, `dependsOn`), and `JevCriterion.CodeCriterion`. The static factories
`JevCriterion.noul(...)`, `score(...)` and `choice(...)` still exist, but they now return
`QuestionCriterion`.

| If your code… | It breaks because… | Do this |
|---|---|---|
| calls only `JevJudge.builder().noul/score/choice(...)` | — | nothing |
| calls `JevCriterion.noul/score/choice(...)` | the factories' return type changed | **recompile**. Code compiled against 0.1.0 fails at runtime with a `LinkageError`, such as `IncompatibleClassChangeError` |
| declares `JevCriterion c = JevCriterion.choice(...)` and reads `c.acceptedOptions()` | the interface only has `name()` | type the variable as `JevCriterion.QuestionCriterion` |
| reads `finding.criterion().question()`, `.minimum()` or `.acceptedOptions()` | same | pattern-match first (below) |
| calls `new JevCriterion(name, question, minimum, options)` | the record constructor is gone | use the factories, or `new JevCriterion.QuestionCriterion(name, question, minimum, options)` |
| deconstructs `JevCriterion(var name, var question, var minimum, var options)` | same, and the record has six components | deconstruct `JevCriterion.QuestionCriterion(var name, var question, var minimum, var options, var appliesWhen, var dependsOn)` |

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

A criterion is now validated however it is built. The constructor rejects what the factories
rejected in 0.1.0:
- a noul `minimum` outside `0..1`
- a score `minimum` above the rubric's highest level
- a choice with no accepted options, or with an accepted option it does not offer
- accepted options on anything but a choice

In 0.1.0, `new JevCriterion(...)` accepted all of these and produced a criterion that could
never pass.

#### `JevFinding.answer()` may be `null`

A finding from a code check has no model answer, so `answer()` is `null`. The same applies
to a `NOT_APPLICABLE` finding: one that was not asked, or whose dependency was not met.
Nothing changes unless you use code checks or conditional criteria. If you do, and you read
`answer()`, guard it:

```java
if (finding.answer() instanceof NoulAnswer noul) { ... }   // false for null, no NPE
```

#### `Outcome` has two new values

`JevFinding.Outcome` gains two values:
- `ERROR`, when the service could not answer a criterion
- `NOT_APPLICABLE`, when a criterion did not apply

**Affected:** an exhaustive `switch` over `Outcome` no longer compiles.

**Migrate:** add the two cases. Neither blocks the verdict by default, so treat them like
`INCONCLUSIVE` if you only care about pass/fail.

#### A missing answer is `ERROR`, not `INCONCLUSIVE`

In 0.1.0, a criterion the service returned no answer for, or one with an answer kind the SDK
could not read, was reported `INCONCLUSIVE`, the same as an ambiguous question. It is now
`ERROR`: an instrument failure, not a verdict on the answer. `failOnInconclusive(true)` no
longer makes it block.

**Migrate:** if missing answers must block, add `failOnError(true)`. The finding is then
`FAILED` and appears in `failures()`. Without it, find outages with `verdict.errors()`
rather than `verdict.inconclusive()`.

#### Pass/fail and confidence come from where the probability lies

In 0.1.0 there were two independent rules:
- **Pass/fail:** a score passed on its expected value, a choice on its single most likely
  label.
- **`INCONCLUSIVE`:** reported when the answer's own `confidence` was below `minConfidence`
  (default 0.5).

`confidence` measures how peaked the whole distribution is. So an answer split between two
*passing* levels was reported undecided, even though the pass was never in doubt. In live
runs, a 4-level `helpfulness` rubric was `INCONCLUSIVE` in 8 of 9 judgements this way.

Both are now decided from the same split of the probability:

- A score **passes** when at least half of its probability is on levels at or above
  `minimum`. A choice passes when at least half is on its accepted options, even if the
  single most likely label is another one. Without probabilities, the value or the selected
  label decides, as before.
- `minConfidence` applies to the share on the verdict's side. The default is now **0.6**, a
  clear majority; a coin flip between pass and fail supports the verdict at 0.5.

**Affected:**
- Judges report fewer `INCONCLUSIVE` scores and choices.
- A few verdicts flip where the expected value and the probability disagree, for example
  `{0: 0.4, 3: 0.6}` against a `minimum` of 2 now passes.
- An explicit `minConfidence(0.5d)` from 0.1.0 is now permissive: 0.5 is the coin-flip
  point.
- A failed choice's feedback now reads
  `tone: 0.68 of the probability is on options outside [helpful, neutral] (most likely "dismissive")`.

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
`TOOL:<name>=<result>` lines. Now `user_question` holds only the system, user and assistant
text. Each assistant tool call is paired, in order, with its result (by id, or by tool name
when the provider leaves the id blank) and sent in its own `tool_calls` field as
`{name, arguments, result}`. Tool calls of earlier turns still in the prompt are included.

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

#### Outages no longer fail the chat call

In 0.1.0, any `TypeSafeException` from the judging call propagated out of the `ChatClient`
call. In 0.2.0 the default is `FAIL_OPEN`. On a transient failure (a connection error or
timeout, a 408, 429 or 5xx), the advisor logs a warning and returns the answer unjudged.
Client errors, such as a bad API key (401), a missing permission (403) or an invalid question
(400, 422), are still rethrown, so a misconfiguration cannot silently turn judging off.

**Affected:** applications that relied on an outage failing the call, where an unjudged
answer must never reach a user.

**Migrate:** restore the 0.1.0 behaviour explicitly:

```java
JevSelfRefineAdvisor.builder()
    .judge(judge)
    .judgeErrorPolicy(JevSelfRefineAdvisor.JudgeErrorPolicy.FAIL_CLOSED)
    .build();
```

Exceptions thrown by a code check are not covered by the policy and always propagate.

#### The best attempt is returned, not the last

Once `maxRepeatAttempts` is exhausted, the advisor used to return the last attempt. It now
returns the attempt with the most criteria passed net of those failed, the later one on a
tie. `JevSelfRefineFailedException.verdict()` carries that attempt's verdict.

**Affected:** only callers who relied on getting the final attempt when every attempt fails.

The default `skipEvaluationPredicate` now also skips a `returnDirect` tool's result, which is
not the model's answer. Judging it could only trigger a retry that re-runs the tool.

#### `supporting_context` is an array

`JevEvaluator` used to send the retrieved documents as one string joined with line
separators. It now sends a JSON array with one entry per document. Documents without text
are skipped.

**Affected:**
- Criteria that refer to `supporting_context` still see the same text, now split per
  document. If a criterion sits close to its threshold, re-check it against a few real cases.
- Code that inspects the request state directly, for example tests using
  `jsonPath("$.state.supporting_context")`.
- A subclass that overrode the `Evaluator` default method `doGetSupportingData`. `JevEvaluator`
  no longer builds the context through it, so the override is bypassed.

**Migrate:**
- In tests, assert on elements, e.g. `$.state.supporting_context[0]`.
- If you need the joined string, call `judge.judge(JsonContent.of(...))` with the state you
  want.
- Instead of overriding `doGetSupportingData`, prepare the documents before building the
  `EvaluationRequest`, or call `JevJudge.judge(JevJudgeInput)` directly.

#### Demo renamed to `ModelJudgeDemoApplication`

In the `examples` module, `LlmJudgeDemoApplication` is now `ModelJudgeDemoApplication`, to
match the Model-as-a-judge terminology. The examples module's default `start-class` follows
the rename. Update any `-Dspring-boot.run.main-class=...demo.judge.LlmJudgeDemoApplication`
you have scripted.

---

### Upgrade checklist

1. Bump to `0.2.0` and **recompile** everything that uses `org.springaicommunity.typesafe.judge`.
2. Fix any compile errors around `JevCriterion` accessors, constructors or record patterns,
   as described [above](#jevcriterion-is-now-a-sealed-interface).
3. Build criteria directly with `new`? Check they are valid: invalid ones now throw.
4. `switch` over `JevFinding.Outcome`? Add `ERROR` and `NOT_APPLICABLE`.
5. Relying on `INCONCLUSIVE` for missing answers, or on `minConfidence(0.5d)`? See
   [the confidence change](#passfail-and-confidence-come-from-where-the-probability-lies)
   and set `failOnError(true)` where missing answers must block.
6. Using `JevSelfRefineAdvisor` with tools? Re-point groundedness criteria at `tool_calls`.
7. Need an outage to fail the chat call? Set `judgeErrorPolicy(FAIL_CLOSED)`.
8. Asserting on the judged state in tests? Update `supporting_context` to the array form.
9. Overriding `JevEvaluator.doGetSupportingData`? Prepare the documents before the request
   instead.
10. Scripts that run `LlmJudgeDemoApplication`? Use `ModelJudgeDemoApplication`.
