SELECT monitoring_profile_id,
       normalized_item_id,
       analysis_event_id,
       source_event_id,
       raw_item_id,
       source_id,
       information_category,
       external_id,
       title,
       url,
       normalized_content,
       content_type,
       relevant,
       classification,
       score,
       explanation,
       analyzer,
       published_at,
       analyzed_at,
       correlation_id,
       traceparent
  FROM results.analyzed_items
 WHERE monitoring_profile_id = :monitoringProfileId
   AND normalized_item_id = :normalizedItemId
