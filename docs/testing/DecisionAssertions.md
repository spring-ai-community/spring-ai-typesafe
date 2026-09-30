# DecisionAssertions

AssertJ assertions that judge a model's answer inside a test, and a JUnit Jupiter extension
that keeps those tests out of an ordinary build. They ship in their own module,
`decision-test`.

!!! warning "Experimental"
    `decision-test` is a prototype, tracked in
    [issue #21](https://github.com/spring-ai-community/spring-ai-typesafe/issues/21). Its
    class names, package and behaviour may change before it is released.

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>decision-test</artifactId>
    <version>0.4.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

## Why

Testing an LLM feature means asserting on text you cannot predict word for word. Two usual
workarounds both fall short:

- **`contains(...)` on the text** is too brittle for a model that paraphrases.
- **`assertThat(verdict.passed()).isTrue()` after a judge call** fails with
  *expected true but was false*. That hides which criterion failed and by how much.

Jev is a good fit for the job. It answers every criterion in one call, returns typed
answers with nothing to parse, and says when it could not decide. These assertions keep
all three properties. When one fails, the message names each criterion that did not pass,
in its own words, with the numbers:

```text
Expected the Jev verdict to pass, but [does_assistant_answer_address_user_question, what_is_the_customer_s_intent, how_polite_is_assistant_answer] did not:
  - [FAILED] does_assistant_answer_address_user_question: the answer to "Does `assistant_answer` address `user_question`?" was no (scored 0.31, needs at least 0.80)
  - [INCONCLUSIVE] what_is_the_customer_s_intent: the options did not settle whether this is one of [billing] (0.55 of the probability supports the verdict, needs at least 0.60)
  - [FAILED] how_polite_is_assistant_answer: rated "Neutral" (0.80), needs to reach 2.00 which is "Polite"
  passed=false [does_assistant_answer_address_user_question=FAILED, what_is_the_customer_s_intent=INCONCLUSIVE, how_polite_is_assistant_answer=FAILED]
```

## Quick Start

```java
import static org.springaicommunity.decision.test.DecisionAssertions.assertThatAnswer;

@SpringBootTest
@DecisionTest
class SupportBotIT {

    @Autowired ChatClient chatClient;

    @Test
    void answersARefundQuestion() {
        String question = "Can I get a refund for last month?";
        String answer = chatClient.prompt(question).call().content();

        assertThatAnswer(answer)
            .givenQuestion(question)
            .satisfies("Does `assistant_answer` address `user_question`?", 0.8)
            .satisfies("Is `assistant_answer` free of refund terms it could not know?")
            .isClassifiedAs("What is the customer's intent?", "billing")
                .among("billing", "technical", "sales")
            .scores("How polite is `assistant_answer`?", "Rude", "Neutral", "Polite", "Very polite")
                .atLeast("Polite")
            .judge();
    }
}
```

`@DecisionTest` supplies the client, so the chain needs no `usingClient(...)`.

## Declaring criteria

Each method adds one criterion and maps it to one of Jev's
[primitives](../concepts/primitives.md):

| Method | Sent as | Passes when |
|---|---|---|
| `satisfies(question)` | `Noul` | the truth value reaches 0.7 |
| `satisfies(question, minimum)` | `Noul` | the truth value reaches `minimum` |
| `isClassifiedAs(question, accepted...).among(options...)` | `Choice` | at least half the probability is on the accepted options |
| `scores(question, levels...).atLeast(level)` | `Score` | at least half the probability is on `level` or above |
| `satisfies(name, Noul, minimum)`, `isClassifiedAs(name, Choice, accepted...)`, `scores(name, Score, minimum)` | as given | as for [`JevJudge`](../judge/JevJudge.md) |
| `criterion(JevCriterion)` | anything | a code check, a dependent criterion, anything a judge takes |

Three differences from what you might expect:

- **A classification names all of its options.** Jev spreads its probability over a fixed
  set of options, so `isClassifiedAs(...)` does nothing until `among(...)` lists them.
- **A score threshold is a level, not a fraction.** A `Score` answers with a position on
  your rubric, such as 1.1 on *Calm, Frustrated, Very angry*. So `atLeast` takes a level's
  wording or its index, never `0.7`. Use `satisfies` if you want a value from 0 to 1.
- **Keep one thing per criterion.** "Answers the question without inventing a refund
  policy" is two criteria. Split it into two `satisfies(...)` calls, so the failure can
  say which one did not hold.

The answer is sent as `assistant_answer`, and the `given...` methods add the other
[`JevJudgeInput`](../judge/JevJudge.md#the-state-a-judge-builds) fields:

- `givenQuestion` → `user_question`
- `givenContext` → `supporting_context`
- `givenToolCall` → `tool_calls`
- `givenExpected` → `expected_output`
- `givenField` → a field of your own

Criteria that name the field they are about are answered more reliably.

## One call, at the end

Nothing is sent while criteria are declared. The chain ends with one of three terminal
methods, and all the criteria are answered in that single call:

| Terminal | What it does |
|---|---|
| `judge()` | asserts that the verdict passed: no criterion failed, was inconclusive or errored |
| `judgeOrAbort()` | fails on a failed criterion; **aborts** the test when the only problem is one Jev could not decide |
| `evaluate()` | asserts nothing; returns the verdict for a test that expects a criterion to fail |

Each one returns a `DecisionVerdictAssert`, so you can keep going:

```java
assertThatAnswer("It is currently -455 degrees Celsius in Paris.")
    .givenQuestion("What is the weather in Paris?")
    .satisfies(PLAUSIBLE)
    .evaluate()
    .failedOn(PLAUSIBLE);

assertThatAnswer(ticket)
    .isClassifiedAs(TEAM, "billing").among("billing", "technical", "sales")
    .judge()
    .finding(TEAM).hasConfidenceAtLeast(0.7);
```

A criterion declared in plain language is named after its own words:
*"Is it polite?"* becomes `is_it_polite`. `finding(...)`, `failedOn(...)` and
`passedOn(...)` accept either that name or the question exactly as you wrote it.

!!! note "Stricter than `JevVerdict.passed()`"
    A verdict passes when nothing *failed*, so an inconclusive criterion does not block
    it. That is right for a live [self-refine loop](../judge/JevSelfRefineAdvisor.md).
    In a test, it would let a question Jev never decided pass silently. `judge()` treats
    `INCONCLUSIVE` and `ERROR` as not passing. `judgeOrAbort()` reports them as
    *aborted*, so an undecided question shows up as skipped rather than as a red build.

To judge with an existing judge instead of declaring criteria, use
`assertThatAnswer(answer).usingJudge(judge).judge()`. `assertThatAnswer(ChatResponse)` judges the text of
the response's first generation.

## @DecisionTest

`@DecisionTest` on a class or method registers `DecisionExtension`, which:

- **skips** the test, rather than failing it, when `TYPESAFE_API_KEY` is not set, so an
  ordinary build never makes a billed call;
- **provides a `TypeSafeClient`**, built once per run from the environment. The chains
  judge with it, and a test method can take it as a parameter;
- **fails a test whose chain never reached a terminal method**. Without one, nothing was
  asserted, and the test would pass for no reason;
- **publishes each verdict's summary** as a report entry (`jev.verdict`). The summary
  appears in the Surefire XML and in the IDE;
- **tags the test `jev`**, so a build can include or exclude these tests as a group.

For a test that judges with its own client, such as one bound to a mock server, use
`@DecisionTest(requiresApiKey = false)`. On a method or a `@Nested` class it overrides a
`@DecisionTest` on the enclosing class, and a `@Nested` class without its own annotation follows
its enclosing class. When something inside opts out, the class itself is no longer
skipped as a whole. Each test is then decided on its own, so the class's `@BeforeAll`
runs even without a key. Outside `@DecisionTest` the assertions still work, but they
need `usingClient(...)`, and nothing detects a chain left without a terminal method.

## Asserting on results you already have

`DecisionAssertions.assertThat(...)` overloads cover the other result types. None of them
calls Jev. They sit next to AssertJ's own `assertThat` in a static import without
clashing:

```java
assertThat(verdict).failedOn("is_plausible").passedOn("helpfulness");
assertThat(verdict).feedback().contains("needs to reach 2.00");

assertThat(response).choice("department").chose("billing").hasProbabilityAtLeast("billing", 0.8);
assertThat(response).noul("is_urgent").isAtLeast(0.7);
assertThat(response).score("frustration").isBetween(1.0, 1.5);

assertThat(JevConsistency.sample(client, state, questions)).isStableAt(0.7);
```

Use the response assertions to pin down how your own questions are answered for a known
state. That way, a change to a question's wording that moves its answers shows up as a
failing test.

## Cost

Every terminal method makes one billed call, however many criteria the chain declares.
Run these tests where you run other live integration tests: this repository keeps them in
`*IT` classes behind `-Pintegration-tests`. The `jev` tag lets you do the same with a
Surefire `groups` / `excludedGroups` filter. Jev's answers can vary a little from run to
run. For a criterion that sits near its threshold, use
[JevConsistency](../patterns/JevConsistency.md) to check whether it is stable before
building a test on it.

## See Also

- [JevJudge](../judge/JevJudge.md): the judge these assertions build, and how each outcome is decided
- [Confidence](../concepts/confidence.md): what `INCONCLUSIVE` means
- [JevConsistency](../patterns/JevConsistency.md): how much an answer moves between runs
