rootProject.name = "signalharvester"

include(
    ":app",
    ":common",
    ":contracts:api-contracts",
    ":contracts:event-contracts",
    ":modules:configuration",
    ":modules:collection",
    ":modules:analysis",
    ":modules:results",
    ":modules:event-observation",
    ":testing:test-support",
    ":testing:integration-tests",
)
