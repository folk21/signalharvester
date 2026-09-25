package io.signalharvester.operations.application;

import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Periodically persists at most one cluster-wide Health Snapshot per configured interval. */
@Singleton
@Requires(property = "signalharvester.operations.health.sampling-enabled", value = "true")
public final class OperationalHealthSampler {
    private static final Logger LOG = LoggerFactory.getLogger(OperationalHealthSampler.class);

    private final OperationalIntelligenceService service;

    public OperationalHealthSampler(OperationalIntelligenceService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelay = "${signalharvester.operations.health.sampling-interval:1m}",
            initialDelay = "${signalharvester.operations.health.sampling-initial-delay:30s}")
    public void sample() {
        try {
            service.captureScheduledSnapshot();
        } catch (RuntimeException failure) {
            LOG.warn("Failed to capture scheduled operational Health Snapshot", failure);
        }
    }
}
