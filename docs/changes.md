# Changes

## 0.4.0 (unreleased)

### New

- **Escalating uncertain criteria:** `JevJudge.Builder.escalateTo(JevEscalation[, threshold])`
  hands the criteria Jev was unsure about (errored, inconclusive, or below the threshold,
  0.9 by default) to a stronger judge. Confident verdicts are kept; the stronger judge's
  answer is judged by the criterion's own rule. `ChatModelEscalation` is the LLM-as-a-judge
  implementation. It follows Li et al.,
  [JEV-as-a-Judge: Accept When Confident, Escalate When Unsure](https://arxiv.org/abs/2609.26550).
  See [Escalating uncertain criteria](judge/JevJudge.md#escalating-uncertain-criteria).
- **Demo:** [`EscalatingJudgeDemoApplication`](demos.md#escalatingjudgedemoapplication) judges
  four answers with and without escalation, side by side.

### Breaking changes

| Change | Migrate |
|---|---|
| **`JevFinding` has a fifth component, `escalated`.** The four-argument constructor is kept. | Record patterns over `JevFinding` need the extra component. |

## 0.3.0

Requests now go out in the same order on every run, retries and RAG screening no longer
fail silently, and the SDK runs against a local [Laya](client/Laya.md) server. A few fixes
change behaviour; each is listed below with how to migrate.

### New

- **`JsonContent.object(key, value, …)`:** a JSON object whose keys keep the order they are
  written in, for state, structured instructions and criteria descriptions. Use it in place
  of a multi-key `Map.of`. See [Key order matters](client/TypeSafeClient.md#key-order-matters).
- **Using Laya:** how to run the SDK against a local, open-source System One server, and
  what differs from Jev. The live ITs follow `TYPESAFE_BASE_URL`, so they can run against
  one too. See [Using Laya](client/Laya.md).

### Fixed

- **Retries silently disabled:** `TypeSafeClient.Builder` counted its 10 s default timeout
  against the retry budget even for a transport you supplied, so a budget of 10 s or less
  refused every retry. The client also warns, when built, if the timeout is at or above the
  budget. See [Errors and Retries](client/ErrorsAndRetries.md).
- **Silent RAG screening failures:** `JevDocumentFilter` logs one warning per batch when
  passages could not be screened and were passed through. `JevDocumentReranker` keeps a
  document unscored, instead of failing the request, when a response lacks the answer.

### Breaking changes

| Change | Migrate |
|---|---|
| **Requests are sent in a fixed key order.** `JevToolIndex`, `JevDocumentFilter`, `JevDocumentReranker` and `JevChatModel` built state and instructions with `Map.of`, whose order changes with every JVM run. The tool index also sent its tools in hash order; it now sends them in the order they were indexed. | Nothing to change, but answers can shift slightly against 0.2.0, and more on order-sensitive servers. Re-check thresholds tuned on exact values, and build your own state with `JsonContent.object`. |
| **`Choice`, `Score` and `SystemOneRequest` copy their collections.** The record constructors snapshot them, and the accessors return unmodifiable views. | Don't change a collection after passing it in, or through `criteria()` or `questions()`. Build a new question or request instead. |
| **`whenChosen` follows the probability.** A branch is taken only when at least half of the choice's probability, and at least `minConfidence`, is on the labels it names. It used to follow the top label alone. | Expect more `NOT_APPLICABLE` dependents on closely split choices. Lower `minConfidence` if a narrow majority should select the branch. |
| **A guardrail refusal is final.** A refused response carries `JevGuardrailAdvisor.OUTCOME_CONTEXT_KEY` in its context, and `JevSelfRefineAdvisor` no longer judges or retries it. | Nothing with the default `skipEvaluationPredicate`. A custom predicate replaces the default, so add the context-key check to keep this. |
| **`JevToolIndex` honours `minimumRelevance`.** When no tool reaches it, none is returned; the top tool used to come back anyway. A `categoryFilter` that matches no tool is ignored instead of returning nothing. | Lower `minimumRelevance` if you relied on always getting a tool. |
| **An undeclared timeout is unknown, not 10 s.** With `restClientBuilder(...)` or `typeSafeApi(...)` and no `timeout(...)`, `TypeSafeClient.timeout()` is `null` and the retry budget bounds only the waits between attempts. | Declare the transport's real timeout with `timeout(...)`. |

## 0.2.0 

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
