SELECT *
FROM operations.human_attention_alerts
WHERE state = 'RESOLVED'
ORDER BY resolved_at DESC, alert_id DESC
LIMIT 1
