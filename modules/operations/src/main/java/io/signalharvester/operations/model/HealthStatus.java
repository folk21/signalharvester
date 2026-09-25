package io.signalharvester.operations.model;

/** Small operational health vocabulary used by persisted snapshots and reports. */
public enum HealthStatus {
    HEALTHY,
    DEGRADED,
    UNHEALTHY,
    UNKNOWN
}
