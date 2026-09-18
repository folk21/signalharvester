INSERT INTO collection.monitoring_profile_schedule_state (
    monitoring_profile_id, interval_minutes, next_due_at, updated_at
) VALUES (:monitoringProfileId, :intervalMinutes, :nextDueAt, :updatedAt)
ON CONFLICT (monitoring_profile_id) DO NOTHING
