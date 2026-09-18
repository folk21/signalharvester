UPDATE analysis.normalized_item_claims
   SET last_raw_item_id = :lastRawItemId,
       last_source_event_id = :lastSourceEventId,
       last_seen_at = :lastSeenAt,
       discovery_count = discovery_count + 1
 WHERE monitoring_profile_id = :monitoringProfileId
   AND normalized_item_id = :normalizedItemId
