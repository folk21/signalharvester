DELETE FROM results.analyzed_item_tags
 WHERE monitoring_profile_id = :monitoringProfileId
   AND normalized_item_id = :normalizedItemId
