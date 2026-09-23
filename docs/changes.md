# Changes

## 0.2.0 (unreleased)

A stronger [Model-as-a-judge](judge/JevJudge.md) API, a sturdier self-refine advisor, and an
experimental [`JevChatModel`](chat/JevChatModel.md). Most upgrades need a recompile and at
most a type rename; each breaking change below says how to migrate.

### New

**Judge**

- **Code criteria:** `JevJudge.Builder.check(name, predicate, defect)`. A plain-Java check
  that runs before the Jev call and lands in the same verdict. See
  [Code criteria](judge/JevJudge.md#code-criteria).
- **Typed input:** `JevJudgeInput` and `judge(JevJudgeInput)`, with fixed state fields:
  `user_question`, `assistant_answer`, `expected_output`, `supporting_context` and
  `tool_calls`.
- **Conditional criteria:**
  - `appliesWhen(predicate)` skips a question when the predicate is false.
  - `whenChosen(choice, labels…)` and `whenPassed(name)` make a question depend on another
    answer, resolved after the single call.
- **Policies:** `failOnError(true)` makes a missing answer block the verdict, and
  `failFast(true)` skips the Jev call once a code check has failed.

**Self-refine advisor**

- **`judgeErrorPolicy`:** `FAIL_OPEN` (the default) returns the answer unjudged on a
  transient judging failure. See [When judging fails](judge/JevSelfRefineAdvisor.md#when-judging-fails).
- **`BEFORE_TOOLS_ORDER`:** a retry re-runs the tools, and each attempt's tool calls are
  recorded for the judge. See [Where it sits](judge/JevSelfRefineAdvisor.md#where-it-sits-and-why).

**Chat model (experimental)**

- **`JevChatModel`:** a Jev classifier behind Spring AI's `ChatModel`, so
  `ChatClient.entity(Record.class)` returns typed classifications. It supports native
  structured output, with the record checked against the questions before the call, and
  standard `gen_ai.client.operation` observations. See [JevChatModel](chat/JevChatModel.md).

**SDK**

- **Criteria-only nouls:** `Noul.builder()` accepts a noul with criteria (`whenTrue` /
  `whenFalse`) and no instructions, as the API does. It still rejects a noul with neither.

### Breaking changes

| Change | Migrate |
|---|---|
| **`JevCriterion` is a sealed interface.** `QuestionCriterion` replaces the old record and adds `appliesWhen` and `dependsOn`; `CodeCriterion` is new. The factories now return `QuestionCriterion`. | Recompile; code compiled against 0.1.0 fails with a `LinkageError`. Read `question()`, `minimum()` and `acceptedOptions()` through `instanceof JevCriterion.QuestionCriterion`. Record patterns now have six components. |
| **Criteria are validated however they are built.** The constructor rejects a `minimum` out of range, a choice without accepted options, and accepted options the choice doesn't offer. | Fix any criterion built with `new` that fails; it could never pass. |
| **`Outcome` has two new values:** `ERROR` and `NOT_APPLICABLE`. | Add both to an exhaustive `switch`. Neither blocks by default. |
| **A missing answer is `ERROR`, not `INCONCLUSIVE`.** | Use `failOnError(true)` where it must block, and read outages from `verdict.errors()`. |
| **Pass/fail follows the probability split.** A score or choice passes when at least half its probability is on passing levels or accepted options. `minConfidence` gates on the share supporting the verdict, and its default moves from 0.5 to **0.6**. | Remove an explicit `minConfidence(0.5d)`, which now means "a coin flip is enough". Expect fewer `INCONCLUSIVE` results. |
| **`JevVerdict.response()` and `JevFinding.answer()` may be `null`** (with code checks, `appliesWhen`, dependencies or `failFast`). | Guard reads when you use those features. |
| **Tool results moved** from `user_question` (`TOOL:<name>=<result>`) into `tool_calls` (`{name, arguments, result}`). | Point groundedness criteria at `` `tool_calls` ``. |
| **Judging outages no longer fail the chat call.** The advisor fails open on transient errors (connection, timeout, 408, 429, 5xx); client errors are still rethrown. | Use `judgeErrorPolicy(FAIL_CLOSED)` to restore the 0.1.0 behaviour. |
| **The best attempt is returned, not the last.** It's the attempt with the most criteria passed net of those failed; the default `skipEvaluationPredicate` also skips `returnDirect` tool results. | Nothing, unless you relied on getting the final attempt. |
| **`supporting_context` is an array,** one entry per document. `JevEvaluator` no longer calls `doGetSupportingData`. | Update tests that assert on the joined string. Prepare documents before the `EvaluationRequest` instead of overriding `doGetSupportingData`. |
| **The demo is renamed** from `LlmJudgeDemoApplication` to `ModelJudgeDemoApplication`. | Update scripted `spring-boot.run.main-class` values. |

`JevSelfRefineAdvisor`'s default order is unchanged: `LOWEST_PRECEDENCE - 2000`.
