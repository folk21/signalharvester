UPDATE security.users
   SET username = :username, enabled = :enabled, updated_at = :updatedAt
 WHERE id = :id
