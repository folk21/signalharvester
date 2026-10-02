UPDATE operations.human_attention_alerts
SET state = 'RESOLVED',
    last_observed_at = :resolvedAt,
    resolved_at = :resolvedAt,
    latest_snapshot_id = :snapshotId,
    latest_health_status = :healthStatus,
    latest_health_score = :healthScore
WHERE alert_id = :alertId
  AND state = 'OPEN'
