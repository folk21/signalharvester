DELETE FROM operations.health_snapshots
WHERE snapshot_id IN (
    SELECT snapshot_id
    FROM operations.health_snapshots
    ORDER BY generated_at DESC, snapshot_id DESC
    OFFSET :keepCount
)
