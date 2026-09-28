SELECT *
FROM operations.health_snapshots
WHERE generated_at >= :instant
ORDER BY generated_at ASC, snapshot_id ASC
LIMIT 1
