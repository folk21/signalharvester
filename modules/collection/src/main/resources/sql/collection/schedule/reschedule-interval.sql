UPDATE collection.monitoring_profile_schedule_state
   SET interval_minutes = :intervalMinutes, next_due_at = :nextDueAt, updated_at = :updatedAt
 WHERE monitoring_profile_id = :monitoringProfileId
   AND interval_minutes <> :intervalMinutes
   AND (lease_until IS NULL OR lease_until <= :now)
