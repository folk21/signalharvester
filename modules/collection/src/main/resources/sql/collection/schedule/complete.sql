UPDATE collection.monitoring_profile_schedule_state
   SET lease_token = NULL,
       lease_until = NULL,
       next_due_at = :nextDueAt,
       updated_at = :updatedAt
 WHERE monitoring_profile_id = :monitoringProfileId
   AND lease_token = :leaseToken
   AND lease_until > :updatedAt
