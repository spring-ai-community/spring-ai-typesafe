# Errors and Retries

Every failure arrives as a `TypeSafeException`, so a caller branches on the exception type
rather than on a status code.

## The hierarchy

```
TypeSafeException
└─ TypeSafeApiException          status, body, headers, endpoint, requestId,
   │                             errorType, errorMessage, validationErrors
   ├─ TypeSafeBadRequestException              400
   ├─ TypeSafeAuthenticationException          401
   ├─ TypeSafePermissionDeniedException        403
   ├─ TypeSafeNotFoundException                404
   ├─ TypeSafeUnprocessableEntityException     422
   ├─ TypeSafeRateLimitException               429   + retryAfterMs
   ├─ TypeSafeInternalServerException          5xx
   │  └─ TypeSafeOverloadedException           529
   └─ TypeSafeApiResponseValidationException         + fieldPath
TypeSafeApiConnectionException
└─ TypeSafeApiTimeoutException
TypeSafeMissingAnswerException, TypeSafeAnswerTypeException    (client side)
```

Twelve of these mirror the official Python SDK one for one, which the JavaScript SDK mirrors
in turn. Three do not, and each earns its place:

- **`TypeSafeOverloadedException`** — the API documents `529 Overloaded` with retry
  guidance, but 529 is numerically a 5xx so both official SDKs fold it into their
  internal-server error. This one names it, and **extends** `TypeSafeInternalServerException`
  rather than sitting beside it, so catching the server error still catches an overload
  exactly as the Python pattern does.
- **`TypeSafeMissingAnswerException` / `TypeSafeAnswerTypeException`** — Python needs no
  equivalent, because `response.answers["nope"]` already raises `KeyError` and reading
  `.choice` off a noul raises `AttributeError`. Java's alternative is a bare
  `NullPointerException` or `ClassCastException`, which name the symptom instead of the
  cause.

## Which status you actually get

Not always the obvious one, which is the argument for branching on the type:

| Situation | Status | Exception |
|-----------|--------|-----------|
| Rejected API key | 401 | `TypeSafeAuthenticationException` |
| **Missing** API key | **403** | `TypeSafePermissionDeniedException` |
| Unknown model | **400** | `TypeSafeBadRequestException` |
| Malformed question | 400 | `TypeSafeBadRequestException` |
| Body fails request validation | 422 | `TypeSafeUnprocessableEntityException` |
| Unrouted path | 404 | `TypeSafeNotFoundException` |

## Reading an error body

Error bodies arrive in a single `detail` envelope that takes three shapes, all read by
`TypeSafeErrorDetail` and reachable from any `TypeSafeApiException`:

| Response | `detail` is | What you get |
|----------|-------------|--------------|
| 400, 401, 403 | an object | `errorType()` (`authentication_error`, `api_usage_error`, …) and `errorMessage()` |
| 422 | an array | `validationErrors()`, each with `type()`, `loc()`, `path()` and `msg()` |
| 404 | a string | `errorMessage()` |

The table describes the hosted API. A compatible server such as [Laya](Laya.md) may send
`detail` as a plain string for every status, so `errorType()` can be `null` and
`validationErrors()` empty, while `errorMessage()` is still set; see
[what differs](Laya.md#what-differs).

```java
catch (TypeSafeApiException ex) {
    log.warn("{} failed: {} [{}] (request {})",
            ex.endpoint(), ex.errorMessage(), ex.errorType(), ex.requestId());
    ex.validationErrors().forEach(e -> log.warn("  {}: {}", e.path(), e.msg()));
}
```

The parse is best-effort and lazy: `body()` always keeps the raw text, so an unrecognised
shape costs nothing and is reported as-is.

A response that arrives but cannot be read — a proxy answering `200` with HTML, or
malformed JSON — is translated too, as `TypeSafeApiResponseValidationException` and
`TypeSafeException` respectively, rather than escaping as a raw Spring exception.

## Retries

Defaults match the official SDKs: **2 retries**, exponential backoff from 500ms to 5s with
25% jitter subtracted, on 408/429/5xx and connection failures, within a **30s budget**. A
429 carrying `retry-after-ms` overrides the computed backoff.

```java
RetryPolicy policy = RetryPolicy.builder()
        .maxRetries(3)
        .initialBackoff(Duration.ofMillis(500))
        .maxBackoff(Duration.ofSeconds(5))
        .jitter(0.25d)
        .totalTimeout(Duration.ofSeconds(30))
        .build();

TypeSafeClient client = TypeSafeClient.builder().retryPolicy(policy).build();
```

`RetryPolicy.noRetry()` turns it off entirely, which is what the offline tests use.

| Method | Default | Description |
|---|---|---|
| `maxRetries(int)` | `2` | Repeats after the first attempt. |
| `initialBackoff(Duration)` | `500ms` | First wait, doubling thereafter. |
| `maxBackoff(Duration)` | `5s` | Ceiling on a single wait. |
| `jitter(double)` | `0.25` | Fraction subtracted at random, to avoid a thundering herd. |
| `retryableStatuses(Set<Integer>)` | `{408, 429}` | Plus anything `>= 500`. |
| `respectRetryAfter(boolean)` | `true` | Honour `retry-after-ms` over the computed backoff. |
| `retryConnectionErrors(boolean)` | `true` | Retry failures with no HTTP response. |
| `totalTimeout(Duration)` | `30s` | Budget for the whole call. |

!!! note "The budget includes the next attempt"
    `totalTimeout` counts the next attempt's own HTTP timeout, not just the wait before it,
    so a call never overruns the budget by a whole request. When the time left cannot fit
    another attempt, the last failure is thrown instead.

    So keep the per-attempt timeout well below the budget. With a timeout at or above it,
    no retry can ever fit and nothing is retried, not even an instant 429; the client logs
    a warning when it is built that way.

    Declare the transport's real timeout with `timeout(...)` when you supply your own
    `RestClient.Builder`, since that is the figure the budget uses. Left undeclared, the
    timeout of your transport is unknown, and the budget bounds only the waits between
    attempts.

## Request ids

Every response carries `x-typesafe-request-id`, exposed as `response.requestId()` and
`exception.requestId()`. It travels with the data it describes — quote it when reporting a
problem.

## See Also

- [TypeSafeClient](TypeSafeClient.md)
- [Batches](Batches.md) — failures are per request, not per batch
- [TypeSafe API reference](https://docs.typesafe.ai/api)
