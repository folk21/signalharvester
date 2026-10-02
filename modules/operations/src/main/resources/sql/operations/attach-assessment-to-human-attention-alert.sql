UPDATE operations.human_attention_alerts
SET latest_assessment_id = :assessmentId,
    model_attention_suggested = :modelAttentionSuggested
WHERE alert_id = :alertId
  AND state = 'OPEN'
