# JevCompositeScore

Several score dimensions combined into one number with weights you control.

!!! warning "For ordering, never for gating"
    The argument this SDK makes for Jev over a single rubric prompt is that independent
    checks must be thresholded **independently**. An answer that is fluent and on topic but
    quotes an impossible temperature has to fail on plausibility alone, and any average
    hides exactly that.

    So do not build a pass/fail decision on a composite. [`JevJudge`](../judge/JevJudge.md)
    thresholds each criterion separately and is the right tool for that.

## What it is genuinely for

Ranking a field of candidates that have **all already passed**: which five of forty
applicants to read first, which support ticket to work next.

There the dimensions are not pass conditions, they are preferences, and the weights are the
policy. Keeping them in code means a disappointing ranking is fixed by changing a weight
rather than by rewording a question.

## Quick Start

```java
JevCompositeScore engineering = JevCompositeScore.builder()
    .weight("python_depth",    0.40d)
    .weight("system_design",   0.40d)
    .weight("team_leadership", 0.10d)
    .weight("generalist",      0.10d)
    .build();

candidates.sort(Comparator.comparingDouble(c -> -engineering.of(c.response())));
```

## Normalisation

Each dimension is divided by its rubric's own height before weighting, so dimensions with
different numbers of levels are comparable. The height is `ScoreAnswer.maxLevel()`, read from
the legend the response carries; an answer without a legend is rejected:

```java
double normalised = JevCompositeScore.normalise(response.score("python_depth"));  // 0.0 – 1.0
```

With weights summing to 1, the composite is itself in `[0, 1]`.

## Same data, different profiles

The point of keeping weights in code is that one set of answers serves several rankings:

```java
JevCompositeScore ic = JevCompositeScore.builder()
    .weight("python_depth", 0.40d).weight("system_design", 0.40d)
    .weight("team_leadership", 0.10d).weight("generalist", 0.10d).build();

JevCompositeScore manager = JevCompositeScore.builder()
    .weight("python_depth", 0.15d).weight("system_design", 0.20d)
    .weight("team_leadership", 0.40d).weight("generalist", 0.25d).build();
```

One call produces the answers; two weightings produce two shortlists.

## Builder Configuration

| Builder method | Type | Default | Description |
|---|---|---|---|
| `weight(String, double)` | — | — | A score criterion's share of the composite. |
| `allowUnnormalisedWeights(boolean)` | `boolean` | `false` | Permit weights that do not sum to 1, which puts the composite's range somewhere other than `[0, 1]`. |

`build()` rejects weights that do not sum to 1 unless you opt out, because weights that do
not sum to one are usually a typo rather than an intention.

## See Also

- [JevJudge](../judge/JevJudge.md) — the right tool when the answer is pass or fail
- [The Three Primitives](../concepts/primitives.md) — why scores are continuous
- [TypeSafe composite-scoring pattern](https://docs.typesafe.ai/patterns/composite-scoring)
