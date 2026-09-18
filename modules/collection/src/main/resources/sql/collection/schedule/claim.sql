UPDATE collection.monitoring_profile_schedule_state
   SET lease_token = :leaseToken, lease_until = :leaseUntil, updated_at = :updatedAt
 WHERE monitoring_profile_id = :monitoringProfileId
   AND interval_minutes = :intervalMinutes
   AND next_due_at <= :now
   AND (lease_until IS NULL OR lease_until <= :now)
RETURNING monitoring_profile_id
