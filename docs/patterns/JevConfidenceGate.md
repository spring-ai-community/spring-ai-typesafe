# JevConfidenceGate

!!! warning "This project is archived"

    `JevConfidenceGate` is now `DecisionConfidenceGate` in Spring AI Decision Extensions (`org.springaicommunity:spring-ai-decision-patterns`), and works with any Spring AI `DecisionModel`. See [DecisionConfidenceGate](https://spring-ai-community.github.io/spring-ai-decision-extensions/latest/patterns/DecisionConfidenceGate/).
    To move an application over, see [Coming from spring-ai-typesafe](https://spring-ai-community.github.io/spring-ai-decision-extensions/latest/migration/#what-behaves-differently).

A reusable routing policy: a universal confidence floor, plus per-action thresholds for
actions that cost more when they are wrong.

## What it does

The answer says *what*; confidence says *whether to act on it unattended*. This turns that
into three decisions:

| Decision | Meaning |
|----------|---------|
| `EXECUTE` | confident enough for this action — act on it |
| `CONFIRM` | above the floor but short of what this action demands — ask the user first |
| `ESCALATE` | below the floor — nobody should act on this automatically |

## Quick Start

```java
JevConfidenceGate gate = JevConfidenceGate.builder()
    .floor(0.60d)
    .require("transfer_funds", 0.85d)
    .build();

switch (gate.decide(response.choice("intent"))) {
    case EXECUTE  -> perform(intent);
    case CONFIRM  -> askTheUserToConfirm(intent);
    case ESCALATE -> handOverToAHuman();
}
```

`decide(ChoiceAnswer)` uses the selected label as the action name. `decide(String, double)`
takes them separately, for when the action is not simply the label:

```java
String action = untroubled ? "auto_close" : "route_to_" + department;
gate.decide(action, response.choice("department").confidence());
```

## Builder Configuration

| Builder method | Type | Default | Description |
|---|---|---|---|
| `floor(double)` | `double` | `0.6` | The confidence below which every action escalates. |
| `require(String, double)` | — | — | Demand more for one action than the floor asks of the rest. |

`build()` rejects an action demanding *less* than the floor — such an action could never be
reached, so it is a configuration mistake rather than a preference.

## Why a class rather than an `if`

Two reasons. The asymmetry between actions is policy, and policy is easier to review,
test and change when it lives in one place than when it is spread across call sites. And
the floor is a genuinely separate idea from the per-action requirement: it is the line
below which the *question* did not decide, regardless of what you were going to do with the
answer.

From the [ticket triage demo](../demos.md#tickettriagedemo):

```
department   : technical (confidence 0.44)
=> route_to_technical: too uncertain to act on, sent to a person
```

A Stripe integration failure is genuinely both a billing and a technical matter. The
confidence lands at 0.44, below the floor, and the gate sends it to a person rather than
guessing — while the other tickets in the same run clear the floor and are handled
automatically.

## See Also

- [Confidence](../concepts/confidence.md)
- [JevConsistency](JevConsistency.md) — checking whether a threshold rests on noise
- [TypeSafe confidence-routing pattern](https://docs.typesafe.ai/patterns/confidence-routing)
