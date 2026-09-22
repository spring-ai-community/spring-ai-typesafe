# Spring AI TypeSafe

A Java client for the [TypeSafe AI](https://docs.typesafe.ai/introduction) **System One** API
(`jev`), plus a [Spring AI](https://docs.spring.io/spring-ai/reference/) integration that uses
it for judging, guardrails, RAG triage and tool selection.

## Overview

Most AI code coerces a text-generation model into emitting structured decisions, then parses
the results back into something the program can depend on. That round trip is where the
failures live: the model answers in a shape the parser did not expect, a rating drifts
between runs, and a single overall score hides the one defect that mattered.

Jev is not a chat model. It takes a **state** and a map of **typed questions**, and returns
structured answers — no text generation, no JSON to coerce, no parsing. Every question in a
call is answered against the same state in parallel, so the documented pattern is *atomic
questions, composed in code* rather than one broad rubric prompt.

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

double urgency     = response.noulValue("is_urgent");           // 0.95
String department  = response.choiceValue("department");        // "billing"
double confidence  = response.choice("department").confidence(); // 0.82
double frustration = response.scoreValue("frustration");        // 1.1
```

**What this SDK adds on top of the API:**

- A typed client with retries, a typed exception hierarchy and a batch API
- `JevJudge` — Model-as-a-judge built from atomic criteria instead of one rubric prompt
- Spring AI integrations that implement the framework's own SPIs: `CallAdvisor`,
  `DocumentPostProcessor`, `ToolIndex` and `Evaluator`

## Project Structure

```
spring-ai-typesafe/
├── typesafe-java-sdk/            # The client: TypeSafeClient, questions, answers, errors, batches
├── typesafe-spring-ai/           # Judge, advisors, RAG post-processors, tool index
├── spring-ai-starter-typesafe/   # spring.ai.typesafe.* properties and a TypeSafeClient bean
├── typesafe-bom/                 # Bill of materials for the three above
└── examples/                     # Seven runnable demos
```

`typesafe-spring-ai` holds four packages:

| Package | Holds | Needs |
|---------|-------|-------|
| `…typesafe.judge` | [`JevJudge`](judge/JevJudge.md), [`JevEvaluator`](judge/JevEvaluator.md), [`JevConfidenceGate`](patterns/JevConfidenceGate.md), [`JevCompositeScore`](patterns/JevCompositeScore.md), [`JevConsistency`](patterns/JevConsistency.md) | — |
| `…typesafe.advisor` | [`JevSelfRefineAdvisor`](judge/JevSelfRefineAdvisor.md), [`JevGuardrailAdvisor`](guardrails/JevGuardrailAdvisor.md) | — |
| `…typesafe.rag` | [`JevDocumentFilter`](rag/JevDocumentFilter.md), [`JevDocumentReranker`](rag/JevDocumentReranker.md) | `spring-ai-rag` |
| `…typesafe.toolsearch` | [`JevToolIndex`](toolsearch/JevToolIndex.md) | `spring-ai-tool-search-tool` |

The last two dependencies are declared `<optional>true</optional>`, so an application that
does not do RAG or tool search never sees those classes and never pays for the dependency.

## Quick Start

**1. Add the dependency:**

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

Plain Java without Spring Boot needs only `typesafe-java-sdk`.

!!! tip "Or import the BOM and drop the versions"
    ```xml
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springaicommunity</groupId>
                <artifactId>typesafe-bom</artifactId>
                <version>0.1.0-SNAPSHOT</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
    ```

    The three artifacts above then need no `<version>` of their own, and cannot drift
    apart from each other.

!!! note
    You need Java 17 or later and, for `typesafe-spring-ai`, Spring AI `2.0.1` or later.

**2. Set your API key:**

```bash
export TYPESAFE_API_KEY=...
```

This is the same variable the official Python and JavaScript SDKs read.
`TypeSafeClient.builder().build()` picks it up with no further configuration.

**3. Ask a question:**

```java
@SpringBootApplication
public class Application {

    @Bean
    CommandLineRunner demo(TypeSafeClient typeSafeClient) {
        return args -> {
            SystemOneResponse response = typeSafeClient.systemOne(
                    "My card was charged twice.",
                    Map.of("refund_requested", Noul.of("Is the customer asking for money back?")));

            if (response.noulValue("refund_requested") > 0.8) {
                startRefund();
            }
        };
    }
}
```

The starter contributes the `TypeSafeClient` bean as soon as `spring.ai.typesafe.api-key`
is set — see [Spring Boot Starter](client/SpringBootStarter.md).

## What to read next

| If you want to… | Read |
|-----------------|------|
| Understand nouls, choices and scores | [The Three Primitives](concepts/primitives.md) |
| Know when to act on an answer automatically | [Confidence](concepts/confidence.md) |
| Evaluate a model's answers | [JevJudge](judge/JevJudge.md) |
| Retry a bad answer automatically | [JevSelfRefineAdvisor](judge/JevSelfRefineAdvisor.md) |
| Screen unsafe input or output | [JevGuardrailAdvisor](guardrails/JevGuardrailAdvisor.md) |
| Improve a RAG pipeline | [JevDocumentFilter](rag/JevDocumentFilter.md) and [JevDocumentReranker](rag/JevDocumentReranker.md) |
| Pick a tool from a large toolset | [JevToolIndex](toolsearch/JevToolIndex.md) |
| Score many items at once | [Batches](client/Batches.md) |
| See it all working | [Demos](demos.md) |

## Requirements

- Java 17+
- Spring Boot 4.x (for the starter)
- Spring AI 2.0.1 or later (for `typesafe-spring-ai`)
- Maven 3.6+
- A TypeSafe API key

## Building

```bash
# Build everything; the suite is offline and needs no key
mvn clean verify

# Add the tests that talk to the real API
export TYPESAFE_API_KEY=...
mvn clean verify -Pintegration-tests

# Run a demo
mvn install -DskipTests
mvn -pl examples spring-boot:run \
    -Dspring-boot.run.main-class=org.springaicommunity.typesafe.demo.TicketTriageDemo
```

Integration tests are gated twice — behind the `integration-tests` profile *and* behind
`TYPESAFE_API_KEY` — so an exported key never turns an ordinary build into a billed one.

## Links

- [TypeSafe AI Documentation](https://docs.typesafe.ai/introduction)
- [TypeSafe API Reference](https://docs.typesafe.ai/api)
- [Spring AI Documentation](https://docs.spring.io/spring-ai/reference/)

## License

Apache License 2.0
