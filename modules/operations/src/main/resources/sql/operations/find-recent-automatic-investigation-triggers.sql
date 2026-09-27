SELECT *
FROM operations.automatic_investigation_triggers
ORDER BY created_at DESC, trigger_id DESC
LIMIT :limit
