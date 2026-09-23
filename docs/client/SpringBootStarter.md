# Spring Boot Starter

Auto-configures a `TypeSafeClient` bean from `spring.ai.typesafe.*` properties.

## Quick Start

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-starter-typesafe</artifactId>
    <version>0.2.0</version>
</dependency>
```

```properties
spring.ai.typesafe.api-key=${TYPESAFE_API_KEY}
```

That is the whole setup. Inject the client anywhere:

```java
@Bean
CommandLineRunner demo(TypeSafeClient typeSafeClient) {
    return args -> {
        SystemOneResponse response = typeSafeClient.systemOne(
                "My card was charged twice.",
                Map.of("refund_requested", Noul.of("Is the customer asking for money back?")));
    };
}
```

## Properties

```properties
spring.ai.typesafe.api-key=${TYPESAFE_API_KEY}
spring.ai.typesafe.base-url=https://api.typesafe.ai
spring.ai.typesafe.model=jev-latest
spring.ai.typesafe.timeout=10s
spring.ai.typesafe.retry.max-retries=2
spring.ai.typesafe.retry.initial-backoff=500ms
spring.ai.typesafe.retry.max-backoff=5s
spring.ai.typesafe.retry.jitter=0.25
spring.ai.typesafe.retry.total-timeout=30s
```

| Property | Default | Description |
|---|---|---|
| `api-key` | — | The API key. **No bean is created without it.** |
| `base-url` | `https://api.typesafe.ai` | The API base URL. |
| `model` | `jev-latest` | Applied to requests that do not name a model. |
| `timeout` | `10s` | Per-attempt HTTP timeout. |
| `retry.*` | see [Errors and Retries](ErrorsAndRetries.md) | Retry budget and backoff. |

## Conditional wiring

The `TypeSafeClient` bean appears **only once `api-key` is set**, so an application that has
not been given a key still starts. Guard your own beans the same way if they depend on it:

```java
@Bean
@ConditionalOnBean(TypeSafeClient.class)
JevJudge answerQualityJudge(TypeSafeClient typeSafeClient) {
    return JevJudge.builder(typeSafeClient)
        .score("helpfulness", helpfulnessRubric, 2.0d)
        .build();
}
```

Defining your own `TypeSafeClient` bean switches the auto-configuration off entirely.

## Environment variables and properties

The plain-Java client falls back to `TYPESAFE_API_KEY`, `TYPESAFE_BASE_URL` and
`TYPESAFE_DEFAULT_MODEL`. Under Boot, `base-url` and `model` always have property defaults
and are always passed explicitly, so **only the key reaches the client from the
environment** — and only through the `${TYPESAFE_API_KEY}` placeholder you write in your
properties.

!!! warning "Do not export TYPESAFE_BASE_URL while developing"
    The plain-Java builder falls back to it, so an exported value silently redirects any
    throwaway `main` you write, and any test that does not pin the base URL itself.

## Endpoints bean

The starter also contributes the configured paths as a
`TypeSafeAutoConfiguration.TypeSafeEndpoints` record, which is occasionally useful for
observability or for building a health check:

```java
@Bean
HealthIndicator jevHealth(TypeSafeClient client,
        TypeSafeAutoConfiguration.TypeSafeEndpoints endpoints) {
    // endpoints.systemOnePath(), endpoints.modelsPath()
}
```

## See Also

- [TypeSafeClient](TypeSafeClient.md) — everything the bean can do
- [Errors and Retries](ErrorsAndRetries.md)
- [JevJudge](../judge/JevJudge.md)
