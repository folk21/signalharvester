SELECT *
FROM operations.human_attention_alerts
ORDER BY opened_at DESC, alert_id DESC
LIMIT :limit
