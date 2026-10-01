package io.signalharvester.operations.alert;

/** Pure deterministic alert-policy result for one new Health Snapshot. */
public sealed interface AlertDecision permits AlertDecision.None, AlertDecision.Open, AlertDecision.Update, AlertDecision.Resolve {
    record None() implements AlertDecision {}
    record Open(HumanAttentionAlertSeverity severity, HumanAttentionAlertReason reason) implements AlertDecision {}
    record Update(HumanAttentionAlertSeverity severity, HumanAttentionAlertReason reason) implements AlertDecision {}
    record Resolve() implements AlertDecision {}
}
