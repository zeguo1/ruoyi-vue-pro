-- Request fingerprints only; no bearer, context, phone, SMS code, or request payload is persisted.
-- Retain for the lifetime of the issuer's operation namespace; do not purge while old operation IDs can be reused.
CREATE TABLE IF NOT EXISTS crm_trial_connector_operation (
  issuer VARCHAR(64) NOT NULL,
  operation_hash CHAR(64) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY (issuer, operation_hash)
);
-- A safe-card confirmation authorizes only this immutable application; it does not start provisioning.
CREATE TABLE IF NOT EXISTS crm_trial_connector_consent (
  application_id VARCHAR(36) PRIMARY KEY,
  identity_hash CHAR(64) NOT NULL,
  action_hash CHAR(64) NOT NULL,
  confirmed_at TIMESTAMP(6) NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL
);
