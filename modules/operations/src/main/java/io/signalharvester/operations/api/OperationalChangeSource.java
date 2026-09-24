package io.signalharvester.operations.api;

/** Origin of a journaled operational change. */
public enum OperationalChangeSource {
    REST_UI,
    SYSTEM,
    TOOLING,
    TEST_SCENARIO,
    RECOVERY
}
