CREATE SCHEMA IF NOT EXISTS dashboard_auth;

CREATE TABLE IF NOT EXISTS dashboard_auth.dashboard_user (
    account_id SMALLINT NOT NULL DEFAULT 1,
    username VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT dashboard_user_singleton CHECK (account_id = 1),
    CONSTRAINT dashboard_user_pk PRIMARY KEY (account_id),
    CONSTRAINT dashboard_user_username_key UNIQUE (username)
);
