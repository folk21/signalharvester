DELETE FROM results.analyzed_item_attributes
 WHERE monitoring_profile_id = :monitoringProfileId
   AND normalized_item_id = :normalizedItemId
