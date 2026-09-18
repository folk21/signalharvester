SELECT live.live_event_id,
       ai.monitoring_profile_id,
       ai.normalized_item_id,
       ai.source_id,
       ai.information_category,
       ai.external_id,
       ai.title,
       ai.url,
       ai.relevant,
       ai.classification,
       ai.score,
       COALESCE(ARRAY(
           SELECT attribute.attribute_key
             FROM results.analyzed_item_attributes attribute
            WHERE attribute.monitoring_profile_id = ai.monitoring_profile_id
              AND attribute.normalized_item_id = ai.normalized_item_id
            ORDER BY attribute.attribute_key
       ), CAST(ARRAY[] AS text[])) AS attribute_keys,
       COALESCE(ARRAY(
           SELECT attribute.attribute_value
             FROM results.analyzed_item_attributes attribute
            WHERE attribute.monitoring_profile_id = ai.monitoring_profile_id
              AND attribute.normalized_item_id = ai.normalized_item_id
            ORDER BY attribute.attribute_key
       ), CAST(ARRAY[] AS text[])) AS attribute_values,
       COALESCE(ARRAY(
           SELECT tag.tag
             FROM results.analyzed_item_tags tag
            WHERE tag.monitoring_profile_id = ai.monitoring_profile_id
              AND tag.normalized_item_id = ai.normalized_item_id
            ORDER BY tag.tag_ordinal
       ), CAST(ARRAY[] AS text[])) AS tags,
       ai.explanation,
       ai.analyzer,
       ai.published_at,
       ai.analyzed_at
  FROM results.live_result_cursors live
  JOIN results.analyzed_items ai
    ON ai.monitoring_profile_id = live.monitoring_profile_id
   AND ai.normalized_item_id = live.normalized_item_id
   AND ai.analysis_event_id = live.analysis_event_id
 WHERE live.live_event_id > :cursor
   AND :throughCursor >= live.live_event_id
<if(hasMonitoringProfileId)>
   AND ai.monitoring_profile_id = :monitoringProfileId
<endif>
<if(hasSourceId)>
   AND ai.source_id = :sourceId
<endif>
<if(hasInformationCategory)>
   AND ai.information_category = :informationCategory
<endif>
<if(hasRelevant)>
   AND ai.relevant = :relevant
<endif>
<if(hasClassification)>
   AND ai.classification = :classification
<endif>
 ORDER BY live.live_event_id ASC
 LIMIT :limit
