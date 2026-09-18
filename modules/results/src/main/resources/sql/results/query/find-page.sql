SELECT ai.monitoring_profile_id,
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
  FROM results.analyzed_items ai
 WHERE TRUE
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
<if(hasAnalyzedFrom)>
   AND ai.analyzed_at >= :analyzedFrom
<endif>
<if(hasAnalyzedTo)>
   AND :analyzedTo >= ai.analyzed_at
<endif>
<if(hasSearch)>
   AND to_tsvector('simple', COALESCE(ai.title, '') || ' ' || ai.normalized_content)
       @@ websearch_to_tsquery('simple', :search)
<endif>
<if(hasCursor)>
   AND (
       :afterAnalyzedAt > ai.analyzed_at
       OR (ai.analyzed_at = :afterAnalyzedAt AND ai.monitoring_profile_id > :afterMonitoringProfileId)
       OR (ai.analyzed_at = :afterAnalyzedAt
           AND ai.monitoring_profile_id = :afterMonitoringProfileId
           AND ai.normalized_item_id > :afterNormalizedItemId)
   )
<endif>
 ORDER BY ai.analyzed_at DESC, ai.monitoring_profile_id, ai.normalized_item_id
 LIMIT :fetchLimit
