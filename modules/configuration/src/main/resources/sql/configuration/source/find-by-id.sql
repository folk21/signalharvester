SELECT s.id,
       s.name,
       s.source_type,
       s.location,
       s.enabled,
       ss.setting_key,
       ss.setting_value
  FROM configuration.sources s
  LEFT JOIN configuration.source_settings ss ON ss.source_id = s.id
 WHERE s.id = :sourceId
 ORDER BY ss.setting_key
