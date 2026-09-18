SELECT role
  FROM security.user_roles
 WHERE user_id = :userId
 ORDER BY role
