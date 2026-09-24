package io.signalharvester.operations.api;

/** Stable target types used for operational timeline filtering and correlation. */
public enum OperationalChangeTargetType {
    SOURCE,
    MONITORING_PROFILE,
    USER,
    DEPLOYMENT,
    DEAD_LETTER_RECORD,
    SCENARIO
}
