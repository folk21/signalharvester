DELETE FROM operations.human_attention_alerts
WHERE state = 'RESOLVED'
  AND alert_id IN (
      SELECT alert_id
      FROM operations.human_attention_alerts
      WHERE state = 'RESOLVED'
      ORDER BY resolved_at DESC, alert_id DESC
      OFFSET :keepCount
  )
