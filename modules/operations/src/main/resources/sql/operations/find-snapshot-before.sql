SELECT *
FROM operations.health_snapshots
WHERE generated_at <= :instant
ORDER BY generated_at DESC, snapshot_id DESC
LIMIT 1
