package io.signalharvester.operations.assisted;

/** Durable lifecycle state for one automatic assisted-investigation trigger. */
public enum AutomaticInvestigationTriggerState {
    PENDING,
    CLAIMED,
    SUCCEEDED,
    EXHAUSTED
}
