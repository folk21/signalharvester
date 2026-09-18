INSERT INTO results.rejected_items (
    source_event_id,
    analysis_event_id,
    raw_item_id,
    normalized_item_id,
    source_id,
    monitoring_profile_id,
    information_category,
    reason_code,
    explanation,
    rejected_at,
    correlation_id,
    traceparent
) VALUES (
    :sourceEventId,
    :analysisEventId,
    :rawItemId,
    :normalizedItemId,
    :sourceId,
    :monitoringProfileId,
    :informationCategory,
    :reasonCode,
    :explanation,
    :rejectedAt,
    :correlationId,
    :traceparent
)
ON CONFLICT (source_event_id) DO UPDATE SET
    analysis_event_id = EXCLUDED.analysis_event_id,
    raw_item_id = EXCLUDED.raw_item_id,
    normalized_item_id = EXCLUDED.normalized_item_id,
    source_id = EXCLUDED.source_id,
    monitoring_profile_id = EXCLUDED.monitoring_profile_id,
    information_category = EXCLUDED.information_category,
    reason_code = EXCLUDED.reason_code,
    explanation = EXCLUDED.explanation,
    rejected_at = EXCLUDED.rejected_at,
    correlation_id = EXCLUDED.correlation_id,
    traceparent = EXCLUDED.traceparent
