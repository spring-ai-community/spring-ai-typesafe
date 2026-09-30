# JevToolIndex

A Spring AI `ToolIndex` that picks tools by asking Jev, rather than by keyword or embedding
proximity — and, unlike either, can report that **no** tool applies.

!!! note "Optional dependency"
    Needs `spring-ai-tool-search-tool`, which `typesafe-spring-ai` declares
    `<optional>true</optional>`.

## Being able to say no

A Lucene or vector index ranks whatever it holds and hands back a best match. Nothing in
either mechanism can report that the query is about something the toolset does not do —
there is no position in an index that means "none of these".

The same is true of a `Choice` on its own: choice probabilities are a distribution over the
options and therefore always sum to one, so something is always ranked first.

So this index asks a **second, independent question** — *does any of these tools serve the
request at all?* Below the applicability threshold the result is empty, and the model is
told about no tools instead of a plausible wrong one.

Both questions ride in a single request, so a search costs one call regardless of how many
tools are indexed.

## Quick Start

```java
JevToolIndex index = JevToolIndex.builder(typeSafeClient).build();

index.indexTools(sessionId, List.of(
        ToolReference.builder().toolName("currentWeather")
                .summary("Returns the current temperature and conditions for a named place").build(),
        ToolReference.builder().toolName("sendEmail")
                .summary("Sends an email message to one or more recipients").build()));

ToolSearchResponse response = index.search(
        new ToolSearchRequest(sessionId, "is it chilly outside in Oslo", 3, null));
```

Because it implements `ToolIndex`, it is interchangeable with the Lucene, regex and vector
implementations behind Spring AI's tool-search advisor.

## What it returns

Each match is a `ToolReference` whose `relevanceScore()` is that tool's share of the choice
distribution, ordered descending and limited by the request's `maxResults`.

```java
for (ToolReference match : response.toolReferences()) {
    System.out.printf("%-16s %.2f  %s%n", match.toolName(), match.relevanceScore(), match.summary());
}
```

## Compared with a lexical index

From the [tool-search demo](../demos.md#toolsearchdemo), over the same five tools:

| Query | `RegexToolIndex` | `JevToolIndex` |
|-------|------------------|----------------|
| create an invoice for Acme Corp | `createInvoice` 7.50 | `createInvoice` 1.00 |
| bill Acme Corp for last month | *(no tool applies)* | `createInvoice` 0.96 |
| is it raining in Amsterdam right now | *(no tool applies)* | `currentWeather` 1.00 |
| drop a line to the finance team | *(no tool applies)* | `sendEmail` 1.00 |
| what is the capital of Peru | *(no tool applies)* | *(no tool applies)* |

Two different things are on display. Rows one and two are the same request in different
words: the baseline finds the tool when the query happens to contain "invoice" and loses it
once a person phrases it naturally. The last row is the one that needs the second question.

## Builder Configuration

| Builder method | Type | Default | Description |
|---|---|---|---|
| `applicabilityThreshold(double)` | `double` | `0.5` | How sure the service must be that *some* tool fits before any is returned. Raise it where calling the wrong tool is worse than calling none. |
| `minimumRelevance(double)` | `double` | `0.0` | Drop tools below this share of the distribution. At zero, every candidate is returned in ranked order. When no tool reaches it, none is returned. |

## Sessions and categories

Tools are indexed per session, matching the `ToolIndex` contract:

```java
index.indexTool(sessionId, toolReference);
index.indexTools(sessionId, toolReferences);
index.clearIndex(sessionId);
```

An unknown or empty session returns an empty result **without a call**. A
`categoryFilter` on the request narrows the candidates before the question is asked: tools
whose name or summary contains it. The category is free text the model writes, so one that
matches no tool is ignored rather than hiding every tool.

A session holding exactly one tool skips the choice entirely — a choice between one option
is not a question worth asking — and asks only whether that tool applies.

## Scale

Every indexed tool becomes an option on the choice, and the whole set is sent on each
search. That is fine for the tens of tools a session realistically holds and wrong for
thousands.

!!! tip "Past a few hundred tools"
    Put a cheap index in front and let Jev choose among its shortlist. A Lucene or vector
    index is good at cutting a large corpus down; this is good at picking correctly from
    what survives, and at declining.

## See Also

- [The Three Primitives](../concepts/primitives.md) — why a choice always names a winner
- [Demos](../demos.md#toolsearchdemo)
- [TypeSafe function-calling cookbook](https://docs.typesafe.ai/cookbooks/function_calling)
