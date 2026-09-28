SELECT *
FROM operations.health_snapshots
ORDER BY generated_at DESC, snapshot_id DESC
LIMIT 1
