# JevConsistency

Asks the same questions repeatedly and reports how much the answers move.

## Why

A single number tells you where an answer sits; it does not tell you whether it would sit
there again. That matters when a value lands near a threshold, because there the difference
between acting and not acting can be noise rather than judgement.

Sampling a question a few times and looking at the spread is how you find out which of your
thresholds rest on solid ground.

## Quick Start

```java
JevConsistency.Report report = JevConsistency.sample(client, state, questions, 15);

report.statistics().forEach((name, stats) ->
        System.out.printf("%-20s mean %.3f  sd %.3f  range %.3f%n",
                name, stats.mean(), stats.standardDeviation(), stats.range()));
```

The overload without a sample count draws `DEFAULT_SAMPLES` (15), the figure the TypeSafe
self-consistency cookbook uses.

## The question this answers

```java
// The questions whose samples fall on both sides of the threshold you rely on
List<String> shaky = report.unstableAt(0.70d);
```

A criterion that straddles its threshold makes a decision that is not reproducible: the same
input would be accepted on one run and rejected on the next. Either move the threshold,
sharpen the question, or route that case to a human.

```java
public record Statistics(String name, List<Double> values, double mean,
                         double standardDeviation, double min, double max) {
    double range();
    boolean straddles(double threshold);
}
```

## How the draws are kept independent

Each sample carries a fresh throwaway `uid` in its state — `JevConsistency.UID_FIELD` —
which is the cookbook's own technique for forcing independent draws rather than a repeat of
one cached computation. The state is otherwise untouched, and the questions are identical
across samples.

That last point matters: this measures the **service's own stability**, not the effect of
rewording a question. Rephrasing to see whether the answer changes is a different and also
useful experiment, but it is not this one.

## What is summarised

Only nouls and scores, which have a single number to track. A choice's stability is a
question about its whole distribution rather than one value, so it is left out of the
statistics — sample it and inspect `probabilities()` yourself if you need it.

## Failures

Samples that fail are collected rather than thrown, so a run still produces statistics over
whatever came back:

```java
if (!report.failures().isEmpty()) {
    log.warn("{} of {} samples failed", report.failures().size(),
             report.failures().size() + report.samples().size());
}
```

## Cost

This is a [batch](../client/Batches.md): fifteen samples are fifteen calls, four at a time
by default. It is a development-time and CI-time tool — run it when choosing a threshold or
when a rubric changes, not on every request.

```java
JevConsistency.sample(client, state, questions, 15, JevBatchOptions.ofConcurrency(8));
```

## See Also

- [Confidence](../concepts/confidence.md) — a different question about one answer
- [Batches](../client/Batches.md)
- [TypeSafe self-consistency cookbook](https://docs.typesafe.ai/cookbooks/consistency_noul_cookbook)
