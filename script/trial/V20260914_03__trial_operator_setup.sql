-- Additive internal bootstrap registry. Never overwrite an existing tenant based on its name.
CREATE TABLE IF NOT EXISTS crm_trial_operator_setup (
    setup_key VARCHAR(32) NOT NULL PRIMARY KEY,
    config_hash VARCHAR(64) NOT NULL,
    tenant_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    package_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_trial_operator_tenant (tenant_id)
);
