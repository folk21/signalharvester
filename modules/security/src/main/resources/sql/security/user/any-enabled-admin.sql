SELECT 1
  FROM security.users u
  JOIN security.user_roles r ON r.user_id = u.id
 WHERE u.enabled = TRUE
   AND r.role = 'ADMIN'
 LIMIT 1
