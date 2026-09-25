INSERT INTO operations.health_snapshots (
    snapshot_id, generated_at, window_started_at, window_ended_at, overall_status,
    health_score, policy_version, component_statuses, signal_values, anomaly_candidates, anomaly_details,
    recent_change_ids, application_version, evidence_complete, unknown_reasons
) VALUES (
    :snapshotId, :generatedAt, :windowStartedAt, :windowEndedAt, :overallStatus,
    :healthScore, :policyVersion, CAST(:componentStatuses AS jsonb), CAST(:signalValues AS jsonb),
    CAST(:anomalyCandidates AS jsonb), CAST(:anomalyDetails AS jsonb), CAST(:recentChangeIds AS jsonb), :applicationVersion,
    :evidenceComplete, CAST(:unknownReasons AS jsonb)
)
