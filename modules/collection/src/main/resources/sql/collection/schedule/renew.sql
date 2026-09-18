UPDATE collection.monitoring_profile_schedule_state
   SET lease_until = :leaseUntil, updated_at = :updatedAt
 WHERE monitoring_profile_id = :monitoringProfileId
   AND lease_token = :leaseToken
