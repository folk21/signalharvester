INSERT INTO security.users
    (id, username, identity_type, enabled, password_hash, created_at, updated_at)
VALUES (:id, :username, :identityType, :enabled, :passwordHash, :createdAt, :updatedAt)
