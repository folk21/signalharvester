UPDATE operations.human_attention_alerts
SET severity = :severity,
    reason = :reason,
    last_observed_at = :observedAt,
    latest_snapshot_id = :snapshotId,
    latest_health_status = :healthStatus,
    latest_health_score = :healthScore
WHERE alert_id = :alertId
  AND state = 'OPEN'
