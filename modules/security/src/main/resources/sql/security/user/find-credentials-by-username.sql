SELECT id, username, identity_type, enabled, password_hash
  FROM security.users
 WHERE LOWER(username) = LOWER(:username)
