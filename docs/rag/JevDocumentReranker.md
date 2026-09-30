# JevDocumentReranker

A Spring AI `DocumentPostProcessor` that reorders retrieved documents by asking, one
document at a time, whether each could actually answer the query.

!!! note "Optional dependency"
    Needs `spring-ai-rag`, which `typesafe-spring-ai` declares `<optional>true</optional>`.

## Why rerank at all

Vector search ranks by embedding proximity, which is a good way to **find** candidates and a
poor way to **order** them: a passage can be about the right subject and still not contain
the answer. Asking a direct question about each candidate is a different measurement, and it
is the one the final ordering should use.

The TypeSafe reranking cookbook reports top-1 accuracy moving from **5% to 18%** on its
evaluation set from this step alone.

## Quick Start

```java
RetrievalAugmentationAdvisor.builder()
    .documentRetriever(retriever)
    .documentPostProcessors(
            JevDocumentFilter.builder(typeSafeClient).build(),      // screen first
            JevDocumentReranker.builder(typeSafeClient).topK(5).build())
    .build();
```

Screening before ranking is the right order: there is no point spending a call ordering a
passage that is about to be thrown out, and an injection should never reach a ranking prompt
in the first place.

## Builder Configuration

| Builder method | Type | Default | Description |
|---|---|---|---|
| `question(Noul)` | `Noul` | a relevance question | Replace the question. Write it about `query` and `passage`, the two state fields. |
| `topK(int)` | `int` | keep all | How many documents to keep after reordering. |
| `minimumScore(double)` | `double` | `0.0` | Drop documents below a bar. At zero this is purely a reordering. |
| `batchOptions(JevBatchOptions)` | — | four at a time | How widely to fan the per-document calls out. |

## Scores travel with the documents

Each scored document carries its value under `SCORE_METADATA_KEY`, so a later stage or a log
can see why the order came out as it did:

```java
ranked.forEach(document -> System.out.printf("%.2f  %s%n",
        document.getMetadata().get(JevDocumentReranker.SCORE_METADATA_KEY),
        document.getId()));
```

## A custom question

The default asks whether the passage could answer the query. Domain-specific rerankings
often want something narrower — the cookbook's own example asks whether a candidate passage
could be from a cited legal precedent:

```java
JevDocumentReranker.builder(typeSafeClient)
    .question(Noul.builder()
        .instructions(JsonContent.object(
            "question", "Could the `passage` be the precedent cited in the `query`?",
            "focus",    "Matching holding and posture, not merely similar subject matter."))
        .whenTrue("The passage states the holding the query cites")
        .whenFalse("A different case, or the same case on a different point")
        .build())
    .build();
```

## Failure behaviour

A document whose call fails, or whose response carries no usable answer, is **kept,
unscored, after every document that was scored**, and the batch logs one warning with the
count. A
transport failure is not evidence that a passage is irrelevant, so dropping it would
silently shrink the context on an unrelated error — but it is not evidence of relevance
either, so an unjudged passage never outranks one this reranker actually measured. That
ordering is what stops a failed call from evicting a known-good passage under `topK`.

Unscored documents carry no `SCORE_METADATA_KEY` entry, so a later stage can tell "scored
zero" from "never scored".

## Cost

One call per document. Reranking a top-20 candidate list is twenty calls, four at a time by
default — see [Batches](../client/Batches.md). Combining with
[`JevDocumentFilter`](JevDocumentFilter.md) first keeps that number down, since only the
survivors are ranked.

!!! tip "Reuse an executor in a server"
    Both post-processors default to `JevBatchOptions.defaults()`, whose executor is `null`,
    so a thread pool is created and shut down on **every** `process()` call — twice per
    request when filter and reranker are chained. In a server, pass a shared one:

    ```java
    JevBatchOptions shared = JevBatchOptions.ofConcurrency(8).withExecutor(myExecutor);

    JevDocumentFilter.builder(typeSafeClient).batchOptions(shared).build();
    JevDocumentReranker.builder(typeSafeClient).batchOptions(shared).build();
    ```

    A caller-supplied executor is never shut down by the batch.

## See Also

- [JevDocumentFilter](JevDocumentFilter.md) — screening before ordering
- [Batches](../client/Batches.md)
- [TypeSafe reranking cookbook](https://docs.typesafe.ai/cookbooks/rerank_typesafe)
