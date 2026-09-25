package io.signalharvester.operations.application;

/** Indicates that no persisted Health Snapshot exists yet. */
public final class HealthSnapshotNotFoundException extends RuntimeException {
    public HealthSnapshotNotFoundException() {
        super("No Health Snapshot has been captured yet");
    }
}
