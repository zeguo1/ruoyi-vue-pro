-- Encrypted singleton configuration. Key material lives in the server's persistent /data volume.
CREATE TABLE IF NOT EXISTS crm_trial_settings (
  id INT PRIMARY KEY,
  revision BIGINT NOT NULL DEFAULT 0,
  operator_tenant_id BIGINT,
  nonce VARCHAR(32),
  ciphertext MEDIUMTEXT,
  updater BIGINT,
  update_time TIMESTAMP(6),
  CHECK (id = 1)
);
INSERT INTO crm_trial_settings(id, revision) SELECT 1, 0
WHERE NOT EXISTS (SELECT 1 FROM crm_trial_settings WHERE id=1);
