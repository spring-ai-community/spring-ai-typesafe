# decision-test

AssertJ assertions that judge a model's answer inside a test, in one Jev call, and a JUnit
Jupiter extension that keeps those tests out of an ordinary build.

📖 Full guide: [DecisionAssertions](https://spring-ai-community.github.io/spring-ai-typesafe/latest-snapshot/testing/DecisionAssertions/)

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>decision-test</artifactId>
    <version>0.4.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

The module depends on `typesafe-spring-ai` and `assertj-core`. `junit-jupiter-api` is
optional, because only `@DecisionTest` needs it.

## Judging an answer

```java
import static org.springaicommunity.decision.test.DecisionAssertions.assertThatAnswer;

@DecisionTest
class SupportBotIT {

    @Test
    void answersARefundQuestion() {
        assertThatAnswer(answer)
            .givenQuestion("Can I get a refund for last month?")
            .satisfies("Does `assistant_answer` address `user_question`?", 0.8)
            .isClassifiedAs("What is the customer's intent?", "billing")
                .among("billing", "technical", "sales")
            .scores("How polite is `assistant_answer`?", "Rude", "Neutral", "Polite", "Very polite")
                .atLeast("Polite")
            .judge();
    }
}
```

| Method | Sent as | Passes when |
|---|---|---|
| `satisfies(question[, minimum])` | `Noul` | the truth value reaches `minimum` (default 0.7) |
| `isClassifiedAs(question, accepted...).among(options...)` | `Choice` | at least half the probability is on the accepted options |
| `scores(question, levels...).atLeast(level)` | `Score` | at least half the probability is on `level` or above |
| `criterion(JevCriterion)` | anything | as for `JevJudge`: a code check, a dependent criterion |

Nothing is sent until the chain ends, and then every criterion is answered in a single call:

| Terminal | What it does |
|---|---|
| `judge()` | fails unless every criterion passed; inconclusive or errored counts as not passing |
| `judgeOrAbort()` | fails on a failed criterion; aborts the test when the only problem is a criterion Jev could not decide |
| `evaluate()` | asserts nothing; returns the verdict for tests that expect a failure |

When an assertion fails, the message names each criterion that did not pass, with its
numbers:

```text
Expected the Jev verdict to pass, but [does_assistant_answer_address_user_question, how_polite_is_assistant_answer] did not:
  - [FAILED] does_assistant_answer_address_user_question: the answer to "Does `assistant_answer` address `user_question`?" was no (scored 0.31, needs at least 0.80)
  - [FAILED] how_polite_is_assistant_answer: rated "Neutral" (0.80), needs to reach 2.00 which is "Polite"
  passed=false [does_assistant_answer_address_user_question=FAILED, how_polite_is_assistant_answer=FAILED]
```

`finding(...)`, `failedOn(...)` and `passedOn(...)` accept a criterion's generated name or
its question as written.

### Configuring the chain

Everything below is optional and goes before the terminal method:

```java
assertThatAnswer(chatResponse)                               // or a String; uses the first generation's text
    .usingClient(typeSafeClient)                             // not needed under @DecisionTest
    .minConfidence(0.7)                                      // choice/score support needed to decide (default 0.6)
    .givenQuestion("Will I need an umbrella in Dublin tomorrow?")   // → user_question
    .givenContext("Met Éireann: 80% chance of rain on Tuesday.")    // → supporting_context
    .givenToolCall(new JevJudgeInput.ToolCall("forecast", "{\"city\":\"Dublin\"}", "rain, 80%"))  // → tool_calls
    .givenExpected("Rain is likely; bring an umbrella.")     // → expected_output
    .givenField("locale", "en-IE")                           // any field of your own
    .satisfies("Is every figure in `assistant_answer` taken from `tool_calls`?")
    .judge();
```

When you need full control over a question, pass the primitive yourself and choose the
criterion's name:

```java
assertThatAnswer(answer)
    .satisfies("is_grounded", Noul.builder()
        .instructions("Is every value in `assistant_answer` supported by `tool_calls`?")
        .whenFalse("States a value no tool returned")
        .build(), 0.8)
    .isClassifiedAs("department", Choice.builder()
        .instructions("Which team should handle `assistant_answer`?")
        .option("billing", "Payments, invoicing, refunds")
        .option("technical", "Bugs, outages, integrations")
        .build(), "billing")
    .scores("helpfulness", helpfulnessRubric, 2.0)           // a Score; passes from level 2
    .scores("How formal is `assistant_answer`?", "Casual", "Neutral", "Formal").atLeast(1)  // level by index
    .criterion(JevCriterion.check("short_enough",
        input -> input.answer().length() < 500, "the answer is longer than 500 characters"))
    .judge();
```

To reuse a `JevJudge` you already have, use `assertThatAnswer(answer).usingJudge(judge).judge()`.
It can't be combined with criteria declared on the chain.

## `@DecisionTest`

`@DecisionTest` registers `DecisionExtension`, which:

- skips the test when `TYPESAFE_API_KEY` is not set;
- provides the `TypeSafeClient` the chains judge with, also available as a test-method
  parameter;
- fails a test whose chain never reached a terminal method;
- publishes each verdict's summary as a `jev.verdict` report entry;
- tags the test `jev`.

For a test that judges with its own client, such as one bound to a mock server, use
`@DecisionTest(requiresApiKey = false)` together with `usingClient(...)`. On a method or `@Nested` class it overrides
the enclosing class's `@DecisionTest`. Once anything inside opts out, the class is no longer
skipped as a whole, so its `@BeforeAll` runs even without a key.

## Asserting on results you already have

The `assertThat(...)` overloads in `DecisionAssertions` never call Jev. They can be
statically imported next to AssertJ's own `assertThat` without clashing:

```java
import static org.assertj.core.api.Assertions.assertThat;
import static org.springaicommunity.decision.test.DecisionAssertions.assertThat;
```

Each chain below uses every method of that assert type.

### Verdicts: `DecisionVerdictAssert`

For a `JevVerdict` from `JevJudge.judge(...)`, or from a chain's `evaluate()`:

```java
assertThat(verdict)
    .passed()                                  // nothing FAILED, INCONCLUSIVE or ERROR
    .passedOn("helpfulness", "is_plausible")   // these criteria PASSED
    .hasNoInconclusive()
    .hasNoErrors();

assertThat(badVerdict)
    .failed()                                  // JevVerdict.passed() is false
    .failedOn("is_plausible");                 // this criterion FAILED

assertThat(verdict).passedOrAborted();         // aborts, rather than fails, when Jev could not decide
assertThat(badVerdict).feedback().contains("needs at least 0.70");   // AssertJ string assert
assertThat(verdict).response().noul("is_plausible").isAtLeast(0.9);  // the raw response, below
assertThat(verdict).finding("helpfulness").isPassed();               // one finding, next section
```

### Findings: `DecisionFindingAssert`

For one criterion's `JevFinding`, reached through `finding(name)` or passed in directly:

```java
assertThat(verdict).finding("helpfulness")
    .hasOutcome(JevFinding.Outcome.PASSED)     // or isPassed(), isFailed(), isInconclusive(), isNotApplicable()
    .hasConfidenceAtLeast(0.7)                 // choices and scores only; a noul has no confidence
    .scoreAnswer().hasNearestLevel(3);         // or noulAnswer(), choiceAnswer()

assertThat(verdict).finding("is_plausible")
    .isFailed()
    .hasDetailContaining("impossible")
    .noulAnswer().isFalse();

assertThat(verdict).finding("mode").isNotApplicable();          // skipped by appliesWhen / whenChosen
assertThat(verdict).finding("department").isInconclusive()
    .choiceAnswer().probabilityOf("billing").isBetween(0.4, 0.6);
```

### Responses: `SystemOneResponseAssert`

For a raw `SystemOneResponse` from `TypeSafeClient.systemOne(...)`. It pins down how your own
questions are answered for a known state:

```java
assertThat(response).hasAnswers("is_urgent", "department", "frustration");
assertThat(response).noul("is_urgent");       // → NoulAnswerAssert
assertThat(response).choice("department");    // → ChoiceAnswerAssert
assertThat(response).score("frustration");    // → ScoreAnswerAssert
```

A missing answer, or one of another kind, fails with the names the response does have.

### Nouls: `NoulAnswerAssert`

A truth value between 0 and 1:

```java
assertThat(response).noul("is_urgent")
    .isTrue()                // leans yes: at least 0.5
    .isAtLeast(0.9)
    .isAtMost(1.0)
    .value().isCloseTo(0.95, within(0.05));   // AssertJ double assert

assertThat(response).noul("is_spam").isFalse();   // leans no: below 0.5
```

### Choices: `ChoiceAnswerAssert`

A selected label, the probability on each option, and a confidence:

```java
assertThat(response).choice("department")
    .chose("billing")                            // the most probable label
    .choseOneOf("billing", "sales")
    .hasProbabilityAtLeast("billing", 0.8)       // the rule a judge applies
    .hasConfidenceAtLeast(0.6)                   // how concentrated the distribution is
    .probabilityOf("technical").isLessThan(0.2); // AssertJ double assert
```

### Scores: `ScoreAnswerAssert`

A position on the rubric's levels, from 0 to the number of levels minus one:

```java
// Score.of("How frustrated is the customer?", "Calm", "Frustrated", "Very angry")
assertThat(response).score("frustration")
    .isAtLeast(1.0)
    .isAtMost(1.5)
    .isBetween(1.0, 1.5)
    .hasNearestLevel(1)                          // "Frustrated" carries the most probability
    .hasConfidenceAtLeast(0.5)
    .value().isGreaterThan(0.9);                 // AssertJ double assert
```

### Consistency: `DecisionConsistencyReportAssert`

For a `JevConsistency.Report`, whether an answer stays on one side of the threshold your code
applies to it:

```java
JevConsistency.Report report = JevConsistency.sample(client, state, questions, 15);

assertThat(report)
    .hasNoFailedSamples()                           // every sample came back
    .isStableAt(0.7)                                // no question straddles 0.7
    .hasStandardDeviationAtMost("is_urgent", 0.05);
```

A failure lists the minimum, maximum, mean and standard deviation of each unstable question.

## Classes

| Class | Asserts on |
|---|---|
| `DecisionAssertions` | entry point: `assertThatAnswer(...)` and the `assertThat(...)` overloads |
| `DecisionAnswerAssert` | an answer, against criteria declared on the chain |
| `DecisionVerdictAssert`, `DecisionFindingAssert` | a `JevVerdict` and one of its findings |
| `SystemOneResponseAssert`, `NoulAnswerAssert`, `ChoiceAnswerAssert`, `ScoreAnswerAssert` | a raw response and its answers |
| `DecisionConsistencyReportAssert` | a `JevConsistency.Report` |
| `DecisionTest`, `DecisionExtension` | the JUnit Jupiter integration |

## Building

```bash
./mvnw -pl decision-test verify                        # offline, against MockRestServiceServer
./mvnw -pl decision-test verify -Pintegration-tests    # adds DecisionAssertionsIT; needs TYPESAFE_API_KEY
```

Every terminal method makes one billed call, however many criteria the chain declares. Keep
these tests in the same tier as your other live integration tests.
