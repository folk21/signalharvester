CREATE SCHEMA IF NOT EXISTS security;

CREATE TABLE security.users (
    id UUID PRIMARY KEY,
    username VARCHAR(200) NOT NULL,
    identity_type VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL,
    password_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT security_users_identity_type_check CHECK (identity_type IN ('HUMAN', 'BOT'))
);

CREATE UNIQUE INDEX security_users_username_ci_idx ON security.users (LOWER(username));

CREATE TABLE security.user_roles (
    user_id UUID NOT NULL REFERENCES security.users(id) ON DELETE CASCADE,
    role VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT security_user_roles_role_check CHECK (role IN ('USER', 'VIEWER', 'ADMIN', 'BOT'))
);
