SELECT monitoring_profile_id, normalized_item_id, source_id, external_id, source_url,
       first_raw_item_id, first_source_event_id, first_seen_at,
       last_raw_item_id, last_source_event_id, last_seen_at, discovery_count
  FROM analysis.normalized_item_claims
 WHERE (:monitoringProfileId IS NULL OR monitoring_profile_id = :monitoringProfileId)
   AND (:sourceId IS NULL OR source_id = :sourceId)
 ORDER BY last_seen_at DESC, normalized_item_id
 LIMIT :limit
