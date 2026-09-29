# Batches

`systemOneAll` answers several independent requests concurrently, with a bounded number in
flight and one result per request.

## When a batch is the right shape

Most of this API is used by packing many questions into **one** call. The service reads the
state once and answers every question against it in parallel, so a third question costs far
less than a third call.

A batch is the other shape — the **same question asked about many different states**. That
is irreducibly one call per item, and it is what several of the documented recipes need:

| Recipe | Why it is one call per item |
|--------|----------------------------|
| [Reranking](../rag/JevDocumentReranker.md) | each (query, passage) pair is scored on its own |
| [Passage classification](../rag/JevDocumentFilter.md) | each passage is screened on its own |
| [Self-consistency](../patterns/JevConsistency.md) | the same request drawn N times |
| Hierarchical classification | one choice per level of the tree |

!!! warning "Not a substitute for batching questions"
    If the states are the same and only the questions differ, you want one `systemOne` call,
    not a batch. It is cheaper, faster and returns the same answers.

## Quick Start

```java
List<JevBatchResult<SystemOneResponse>> results =
        client.systemOneAll(requests, JevBatchOptions.ofConcurrency(4));

for (JevBatchResult<SystemOneResponse> result : results) {
    if (result.succeeded()) {
        handle(result.orThrow());
    }
    else {
        log.warn("request {} failed: {}", result.index(), result.failure().getMessage());
    }
}
```

The single-argument overload uses `JevBatchOptions.defaults()`.

## Results stay aligned with requests

`results.get(i)` always answers `requests.get(i)`, whatever order the calls completed in.
Each result also carries its own `index()`.

```java
public record JevBatchResult<T>(int index, @Nullable T value, @Nullable TypeSafeException failure) {
    boolean succeeded();
    T orThrow();       // rethrows the original typed exception
    T orElse(T fallback);
}
```

## One failure does not cost the batch

A request that fails for good puts its typed exception in its own slot and the rest still
run. That matters for exactly the recipes above: losing forty good passage scores to one
bad call would be a poor trade.

```java
// Everything that came back, ignoring the failures
List<SystemOneResponse> ok = results.stream()
        .filter(JevBatchResult::succeeded)
        .map(JevBatchResult::orThrow)
        .toList();
```

Transient failures are retried *inside* each request by the client's own
[retry policy](ErrorsAndRetries.md) before a slot is recorded as failed, so a slot holding a
failure means the request was retried and still did not succeed.

## Configuration

```java
JevBatchOptions options = JevBatchOptions.ofConcurrency(8)
        .withFailFast(true)
        .withExecutor(myExecutor);
```

| Method | Default | Description |
|---|---|---|
| `JevBatchOptions.defaults()` | concurrency 4, collect failures, own pool | The usual case. |
| `ofConcurrency(int)` | — | How many requests may be in flight at once. |
| `withFailFast(boolean)` | `false` | Stop submitting after the first failure. Requests that had not started report "Batch aborted". |
| `withExecutor(Executor)` | `null` | Run on your executor, which the batch never shuts down. Without one, a pool is created and disposed per batch. |

**Why four?** It is the pooling the TypeSafe passage-classification cookbook uses, and it
sits far below the published limit of 1,200 requests per minute. Raise it when your
per-batch latency matters more than your headroom against the rate limit; a 429 is retried
by the retry policy, but it is still a wasted round trip.

!!! note "Java 17"
    The SDK targets Java 17 and so cannot create a virtual-thread executor itself. On a
    newer runtime, pass one with `withExecutor(...)`.

## Worked example: scoring a candidate list

```java
List<SystemOneRequest> requests = passages.stream()
        .map(passage -> SystemOneRequest.builder()
                .state(JsonContent.object("query", query, "passage", passage.text()))
                .model(client.defaultModel())
                .question("answers_query", relevanceQuestion)
                .build())
        .toList();

List<JevBatchResult<SystemOneResponse>> results = client.systemOneAll(requests);

List<Passage> ranked = IntStream.range(0, passages.size())
        .filter(i -> results.get(i).succeeded())
        .boxed()
        .sorted(Comparator.comparingDouble(
                i -> -results.get(i).orThrow().noulValue("answers_query")))
        .map(passages::get)
        .toList();
```

[`JevDocumentReranker`](../rag/JevDocumentReranker.md) is this, packaged as a Spring AI
`DocumentPostProcessor`.

## See Also

- [TypeSafeClient](TypeSafeClient.md)
- [JevDocumentReranker](../rag/JevDocumentReranker.md) and [JevDocumentFilter](../rag/JevDocumentFilter.md)
- [JevConsistency](../patterns/JevConsistency.md)
