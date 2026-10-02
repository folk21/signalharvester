SELECT *
FROM operations.human_attention_alerts
WHERE state = 'OPEN'
ORDER BY opened_at DESC, alert_id DESC
LIMIT 1
