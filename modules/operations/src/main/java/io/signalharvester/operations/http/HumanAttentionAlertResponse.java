package io.signalharvester.operations.http;

import io.signalharvester.operations.alert.HumanAttentionAlert;
import io.signalharvester.operations.alert.HumanAttentionAlertReason;
import io.signalharvester.operations.alert.HumanAttentionAlertSeverity;
import io.signalharvester.operations.alert.HumanAttentionAlertState;
import io.signalharvester.operations.model.HealthStatus;
import java.time.Instant;
import java.util.UUID;

/** REST representation of one application-owned human-attention alert. */
public record HumanAttentionAlertResponse(
        UUID id,
        HumanAttentionAlertState state,
        HumanAttentionAlertSeverity severity,
        HumanAttentionAlertReason reason,
        String policyVersion,
        Instant openedAt,
        Instant lastObservedAt,
        Instant resolvedAt,
        UUID firstSnapshotId,
        UUID latestSnapshotId,
        HealthStatus latestHealthStatus,
        int latestHealthScore,
        UUID latestAssessmentId,
        Boolean modelAttentionSuggested) {

    static HumanAttentionAlertResponse from(HumanAttentionAlert value) {
        return new HumanAttentionAlertResponse(
                value.id(),
                value.state(),
                value.severity(),
                value.reason(),
                value.policyVersion(),
                value.openedAt(),
                value.lastObservedAt(),
                value.resolvedAt(),
                value.firstSnapshotId(),
                value.latestSnapshotId(),
                value.latestHealthStatus(),
                value.latestHealthScore(),
                value.latestAssessmentId(),
                value.modelAttentionSuggested());
    }
}
