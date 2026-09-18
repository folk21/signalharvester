SELECT COALESCE(MAX(live_event_id), 0)
  FROM results.live_result_cursors
