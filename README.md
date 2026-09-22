# Spring AI TypeSafe

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Maven Central](https://img.shields.io/maven-central/v/org.springaicommunity/typesafe-java-sdk.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/org.springaicommunity/typesafe-java-sdk)
[![Java Version](https://img.shields.io/badge/Java-17%2B-orange)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)


A Java client for the [TypeSafe AI](https://docs.typesafe.ai/introduction) **System One**
API (`jev`), built on Spring `RestClient` and Jackson 3, plus Spring AI integrations that
use it as an LLM-as-a-judge, a guardrail, a RAG post-processor and a tool index.

📖 **[Reference documentation](https://spring-ai-community.github.io/spring-ai-typesafe/latest-snapshot/)**

## Why

Jev is not a chat model. It takes a **state** and a map of **typed questions**, and returns
structured answers — no text generation, no JSON to parse, no schema to coerce a model into
honouring. Every question in a call is answered against the same state in parallel, so the
pattern is *atomic questions, composed in code* rather than one broad rubric prompt.

```java
SystemOneResponse response = typeSafeClient.systemOne(
        "Help! My payouts have been failing for 3 days.",
        Map.of(
            "is_urgent",   Noul.of("Does this convey urgency?"),
            "department",  Choice.builder()
                    .instructions("Which team should handle this?")
                    .option("billing",   "Payments, invoicing, refunds")
                    .option("technical", "Bugs, outages, integrations")
                    .option("sales",     "Pricing, upgrades, new accounts")
                    .build(),
            "frustration", Score.of("How frustrated is the customer?",
                    "Calm", "Frustrated", "Very angry")));

double urgency     = response.noulValue("is_urgent");            // 0.95
String department  = response.choiceValue("department");         // "billing"
double confidence  = response.choice("department").confidence(); // 0.82
double frustration = response.scoreValue("frustration");         // 1.1
```

The three primitives — `Noul`, `Choice` and `Score` — are described in
[Concepts](https://spring-ai-community.github.io/spring-ai-typesafe/latest-snapshot/concepts/primitives/).

## Modules

| Module | What it is |
| ------ | ---------- |
| `typesafe-java-sdk` | The client: `TypeSafeClient`, the question/answer model, retries, batches, typed exceptions. No Spring AI dependency. |
| `typesafe-spring-ai` | `JevJudge`, the advisors, and the RAG and tool-search integrations, on Spring AI's own SPIs |
| `spring-ai-starter-typesafe` | `spring.ai.typesafe.*` properties and an auto-configured `TypeSafeClient` bean |
| `typesafe-bom` | Bill of materials for the three above |
| `examples` | Seven runnable demos |

## Quick start

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>spring-ai-starter-typesafe</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- For the judge, advisors and Spring AI integrations -->
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>typesafe-spring-ai</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```bash
export TYPESAFE_API_KEY=...
```

Plain Java without Spring Boot needs only `typesafe-java-sdk`. Java 17 or later; Spring AI
`2.0.1` or later for `typesafe-spring-ai`. Importing `typesafe-bom` lets you drop the
versions — see
[Getting Started](https://spring-ai-community.github.io/spring-ai-typesafe/latest-snapshot/#quick-start).

## What you can build with it

| | |
| --- | --- |
| [JevJudge](https://spring-ai-community.github.io/spring-ai-typesafe/latest-snapshot/judge/JevJudge/) | LLM-as-a-judge from atomic criteria rather than one rubric prompt |
| [JevSelfRefineAdvisor](https://spring-ai-community.github.io/spring-ai-typesafe/latest-snapshot/judge/JevSelfRefineAdvisor/) | Judge an answer, feed the defect back, retry |
| [JevGuardrailAdvisor](https://spring-ai-community.github.io/spring-ai-typesafe/latest-snapshot/guardrails/JevGuardrailAdvisor/) | Screen prompts and answers; no retry, because an unsafe answer is not a draft |
| [JevDocumentFilter](https://spring-ai-community.github.io/spring-ai-typesafe/latest-snapshot/rag/JevDocumentFilter/) / [JevDocumentReranker](https://spring-ai-community.github.io/spring-ai-typesafe/latest-snapshot/rag/JevDocumentReranker/) | Triage retrieved passages, then order what survives |
| [JevToolIndex](https://spring-ai-community.github.io/spring-ai-typesafe/latest-snapshot/toolsearch/JevToolIndex/) | Tool selection that can answer "none of these apply" |
| [Composing decisions](https://spring-ai-community.github.io/spring-ai-typesafe/latest-snapshot/patterns/JevConfidenceGate/) | Confidence gates, self-consistency, weighted composite scores |

## Building

```bash
./mvnw clean verify                      # offline: no key, no cost
./mvnw clean verify -Pintegration-tests  # adds the live ITs
```

The whole default suite runs offline, with HTTP exercised through `MockRestServiceServer`.
The `*IT.java` classes talk to the real API and are opt-in twice over — behind the profile
*and* behind `@EnabledIfEnvironmentVariable` on `TYPESAFE_API_KEY` — so an exported key
never turns an ordinary build into a billed one.

> [!WARNING]
> While working on the SDK, do not export `TYPESAFE_BASE_URL` or `TYPESAFE_DEFAULT_MODEL`.
> `TypeSafeClient.builder()` falls back to both, and any throwaway `main` you write will
> pick them up.

Run the demos from
[Demos](https://spring-ai-community.github.io/spring-ai-typesafe/latest-snapshot/demos/).

## Documentation

The reference documentation lives in [`docs/`](docs/) and is published by the
[docs workflow](.github/workflows/docs.yml) on every push to `main`. To preview locally:

```bash
pip install mkdocs-material mike
mkdocs serve
```

## Links

- [Reference documentation](https://spring-ai-community.github.io/spring-ai-typesafe/latest-snapshot/)
- [TypeSafe AI documentation](https://docs.typesafe.ai/introduction) and
  [cookbooks](https://docs.typesafe.ai/cookbooks)
- [Spring AI reference](https://docs.spring.io/spring-ai/reference/)

## License

[Apache License 2.0](LICENSE.txt)
