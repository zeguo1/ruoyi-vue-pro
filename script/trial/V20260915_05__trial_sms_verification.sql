-- Private safe-card verification state. Codes and verification bearer values are never stored in plaintext.
CREATE TABLE IF NOT EXISTS crm_trial_sms_challenge (
  id VARCHAR(36) PRIMARY KEY,
  issuer VARCHAR(64) NOT NULL,
  identity_hash CHAR(64) NOT NULL,
  mobile VARCHAR(16) NOT NULL,
  send_key_hash CHAR(64) NOT NULL,
  code_hash CHAR(64) NOT NULL,
  state VARCHAR(24) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  verify_key_hash CHAR(64) NULL,
  proof_hash CHAR(64) NULL,
  proof_expires_at TIMESTAMP(6) NULL,
  verified_at TIMESTAMP(6) NULL,
  application_id VARCHAR(36) NULL,
  UNIQUE KEY uk_trial_sms_send (issuer, send_key_hash),
  UNIQUE KEY uk_trial_sms_proof (proof_hash),
  KEY ix_trial_sms_mobile (mobile, created_at),
  KEY ix_trial_sms_identity (identity_hash, created_at)
);
CREATE TABLE IF NOT EXISTS crm_trial_verified_contact (
  application_id VARCHAR(36) PRIMARY KEY,
  challenge_id VARCHAR(36) NOT NULL,
  mobile VARCHAR(16) NOT NULL,
  verified_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_trial_contact_challenge (challenge_id)
);
