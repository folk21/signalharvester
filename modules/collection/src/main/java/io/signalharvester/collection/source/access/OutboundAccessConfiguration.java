package io.signalharvester.collection.source.access;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.NotNull;

/**
 * Configures runtime authorization for external-source network destinations.
 */
@Context
@ConfigurationProperties("signalharvester.collection.outbound-access")
public interface OutboundAccessConfiguration {

    /** Operating mode for external-source destination authorization. */
    enum Mode {
        /** Reject non-public destinations unless they are explicitly allowlisted. */
        SECURE,
        /** Permit local/private destinations for deterministic trusted development workflows. */
        TRUSTED_LOCAL
    }

    /**
     * Returns the outbound access mode.
     *
     * @return configured destination-policy mode
     */
    @NotNull
    @Bindable(defaultValue = "TRUSTED_LOCAL")
    Mode getMode();

    /**
     * Returns comma-separated CIDR ranges explicitly allowed in secure mode.
     *
     * @return operator-controlled CIDR allow rules, or an empty string
     */
    @Bindable(defaultValue = "")
    String getAllowedCidrs();
}
