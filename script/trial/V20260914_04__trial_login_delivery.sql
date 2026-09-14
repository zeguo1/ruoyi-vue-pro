-- Encrypted escrow of the ordinary user's original login password. No plaintext credential in trial records.
CREATE TABLE IF NOT EXISTS crm_trial_login_delivery (
    application_id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    encryption_key_id VARCHAR(64) NOT NULL,
    nonce VARCHAR(32) NULL,
    ciphertext VARCHAR(256) NULL,
    claim_hash VARCHAR(64) NULL,
    retry_until TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL
);
