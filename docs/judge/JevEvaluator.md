# JevEvaluator

A [`JevJudge`](JevJudge.md) behind Spring AI's `Evaluator` interface, so Jev drops into code
already written against the framework's evaluation SPI.

## Why

Spring AI's `org.springframework.ai.evaluation.Evaluator` is a one-method interface:

```java
EvaluationResponse evaluate(EvaluationRequest request);
```

Its two shipped implementations — `RelevancyEvaluator` and `FactCheckingEvaluator` — prompt
a chat model and parse a verdict out of the prose. `JevEvaluator` implements the same
interface with typed questions instead, so anything holding an `Evaluator` gets a stricter
one without changing a line.

## Quick Start

```java
Evaluator evaluator = new JevEvaluator(judge);

EvaluationResponse result = evaluator.evaluate(
        new EvaluationRequest(userQuestion, retrievedDocuments, assistantAnswer));

if (!result.isPass()) {
    log.warn("{} (score {})", result.getFeedback(), result.getScore());
}
```

## The mapping

| `EvaluationRequest` | Judged state field |
|---|---|
| `getUserText()` | `user_question` |
| `getResponseContent()` | `assistant_answer` |
| `getDataList()` | `supporting_context`, one entry per document (only when non-empty) |

| `EvaluationResponse` | From |
|---|---|
| `isPass()` | `verdict.passed()` |
| `getScore()` | the fraction of criteria that passed |
| `getFeedback()` | `verdict.feedback()` |
| `getMetadata()` | the full `JevVerdict` and a per-criterion outcome map |

```java
JevVerdict verdict = (JevVerdict) result.getMetadata().get(JevEvaluator.VERDICT_METADATA_KEY);
Map<String, String> findings =
        (Map<String, String>) result.getMetadata().get(JevEvaluator.FINDINGS_METADATA_KEY);
// {helpfulness=PASSED, is_plausible=FAILED}
```

!!! note "The single score is lossy on purpose"
    `EvaluationResponse` has room for one float, while a judge holds one threshold per
    criterion. The score is the fraction of criteria that passed, and the per-criterion
    detail is preserved in the metadata. If that view is what you are after, call
    [`JevJudge.judge`](JevJudge.md) directly and read the `JevVerdict`.

    An inconclusive criterion counts as neither passed nor failed, so it lowers the score
    without being treated as a failure — the same stance `JevVerdict.passed()` takes.

## Supporting documents

`getDataList()` carries the retrieved `Document`s, which is the evidence a groundedness
criterion needs. Their texts reach the state as the `supporting_context` array, one entry per
document, so write such a criterion against that field:

```java
JevJudge grounded = JevJudge.builder(typeSafeClient)
    .noul("is_grounded", Noul.builder()
        .instructions("Is every claim in `assistant_answer` supported by `supporting_context`?")
        .whenFalse("Introduces facts the context does not support")
        .build(), 0.7d)
    .build();
```

This is also how a groundedness check gets real evidence in a RAG setting. The
[self-refine advisor](JevSelfRefineAdvisor.md) sees the retrieved context only at its default
order, inside the retrieval advisor.

## See Also

- [JevJudge](JevJudge.md)
- [JevDocumentFilter](../rag/JevDocumentFilter.md) — screening the context before it is used
- [Spring AI evaluation testing](https://docs.spring.io/spring-ai/reference/api/testing.html)
