INSERT INTO results.analyzed_item_tags (
    monitoring_profile_id,
    normalized_item_id,
    tag_ordinal,
    tag
) VALUES (
    :monitoringProfileId,
    :normalizedItemId,
    :tagOrdinal,
    :tag
)
