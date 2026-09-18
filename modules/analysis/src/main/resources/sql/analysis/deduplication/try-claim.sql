INSERT INTO analysis.normalized_item_claims (
    monitoring_profile_id,
    normalized_item_id,
    source_id,
    external_id,
    source_url,
    first_raw_item_id,
    first_source_event_id,
    first_seen_at,
    last_raw_item_id,
    last_source_event_id,
    last_seen_at,
    discovery_count
) VALUES (
    :monitoringProfileId, :normalizedItemId, :sourceId, :externalId, :sourceUrl,
    :firstRawItemId, :firstSourceEventId, :firstSeenAt,
    :lastRawItemId, :lastSourceEventId, :lastSeenAt, 1
)
ON CONFLICT (monitoring_profile_id, normalized_item_id) DO NOTHING
