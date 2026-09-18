INSERT INTO results.analyzed_item_attributes (
    monitoring_profile_id,
    normalized_item_id,
    attribute_key,
    attribute_value
) VALUES (
    :monitoringProfileId,
    :normalizedItemId,
    :attributeKey,
    :attributeValue
)
