INSERT INTO operations.automatic_investigation_triggers (
    trigger_id,
    snapshot_id,
    trigger_type,
    state,
    created_at,
    next_attempt_at,
    attempt_count
) VALUES (
    :triggerId,
    :snapshotId,
    :triggerType,
    'PENDING',
    :createdAt,
    :nextAttemptAt,
    0
)
ON CONFLICT (snapshot_id) DO NOTHING
