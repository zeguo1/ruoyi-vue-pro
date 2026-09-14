-- Additive migration. Apply only to the designated demonstration instance.
-- Global orchestration tables deliberately use JDBC with explicit ownership checks;
-- they are not exposed through tenant-scoped CRUD mappers.
CREATE TABLE IF NOT EXISTS crm_trial_guard (
  id INT PRIMARY KEY
);
INSERT IGNORE INTO crm_trial_guard (id) VALUES (1);
CREATE TABLE IF NOT EXISTS crm_trial_application (
  id VARCHAR(36) PRIMARY KEY,
  issuer VARCHAR(64) NOT NULL,
  subject_id VARCHAR(128) NOT NULL,
  identity_hash CHAR(64) NOT NULL,
  operator_tenant_id BIGINT NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  team VARCHAR(100) NOT NULL,
  contact_name VARCHAR(30) NOT NULL,
  verified_email VARCHAR(254) NOT NULL,
  scenario VARCHAR(32) NOT NULL,
  source VARCHAR(32) NOT NULL,
  policy_json TEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  confirmed_at TIMESTAMP NULL,
  expires_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  bound_at TIMESTAMP NULL,
  first_business_at TIMESTAMP NULL,
  UNIQUE KEY uk_trial_identity (identity_hash),
  UNIQUE KEY uk_trial_request (issuer, idempotency_key),
  KEY ix_trial_expiry (status, expires_at)
);
CREATE TABLE IF NOT EXISTS crm_trial_step (
  application_id VARCHAR(36) NOT NULL,
  step VARCHAR(32) NOT NULL,
  state VARCHAR(24) NOT NULL,
  result_json TEXT NULL,
  error_code VARCHAR(64) NULL,
  attempts INT NOT NULL DEFAULT 0,
  lease_id VARCHAR(36) NULL,
  lease_until TIMESTAMP NULL,
  updated_at TIMESTAMP NOT NULL,
  PRIMARY KEY (application_id, step)
);
CREATE TABLE IF NOT EXISTS crm_trial_nonce (
  key_id VARCHAR(64) NOT NULL,
  nonce VARCHAR(64) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  PRIMARY KEY (key_id, nonce)
);
CREATE TABLE IF NOT EXISTS crm_trial_event (
  issuer VARCHAR(64) NOT NULL,
  event_id VARCHAR(128) NOT NULL,
  application_id VARCHAR(36) NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (issuer, event_id)
);
CREATE TABLE IF NOT EXISTS crm_trial_account (
  application_id VARCHAR(36) PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  KEY ix_trial_tenant (tenant_id),
  UNIQUE KEY uk_trial_user (user_id)
);
CREATE TABLE IF NOT EXISTS crm_trial_business_operation (
  application_id VARCHAR(36) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  follow_up_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  PRIMARY KEY(application_id, idempotency_key)
);
-- Reference the existing OAuth store; do not duplicate plaintext tokens in application/step records.
CREATE TABLE IF NOT EXISTS crm_trial_authorization (
  application_id VARCHAR(36) PRIMARY KEY,
  authorization_ref VARCHAR(36) NOT NULL,
  refresh_token_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  UNIQUE KEY uk_trial_authorization_ref (authorization_ref)
);
