SELECT u.id, u.username, u.identity_type, u.enabled, u.created_at, u.updated_at
  FROM security.users u
 ORDER BY LOWER(u.username), u.id
