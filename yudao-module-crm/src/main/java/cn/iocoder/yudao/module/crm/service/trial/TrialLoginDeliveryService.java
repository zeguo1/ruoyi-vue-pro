package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
@RequiredArgsConstructor
public class TrialLoginDeliveryService {
    private final TrialStore store;
    private final JdbcTemplate jdbc;
    private final TrialLoginVault vault;
    private final AdminUserService users;
    private final TenantService tenants;
    public record Credential(String username, String password, long tenantId, String loginUrl, String expiresAt, String retryUntil) {
        @Override public String toString() { return "TrialLoginCredential[REDACTED]"; }
    }

    /** Called only inside the account creation transaction; never reset an already-created account password. */
    public void prepare(TrialStore.Application app, long userId, String password) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) { throw TrialException.unavailable(); }
        var envelope = vault.encrypt(binding(app, userId), password);
        jdbc.update("INSERT INTO crm_trial_login_delivery(application_id,user_id,encryption_key_id,nonce,ciphertext,created_at) VALUES(?,?,?,?,?,?)",
                app.id(), userId, envelope.keyId(), envelope.nonce(), envelope.ciphertext(), Timestamp.from(Instant.now()));
    }

    /** Private service-to-service response for an original applicant's safe card. Never an Agent tool result. */
    public Credential claim(String id, TrialIdentity identity) {
        return store.locked(id, app -> {
            store.owned(id, identity);
            if (!"READY".equals(app.status()) || app.confirmedAt() == null || !app.expiresAt().isAfter(Instant.now())) { throw unavailable(); }
            if (identity.idempotencyKey() == null || !identity.idempotencyKey().matches("[a-zA-Z0-9_.:-]{16,128}")) { throw unavailable(); }
            for (String step : TrialStore.PROVISION_STEPS) {
                if (!"DONE".equals(store.step(id, step).state())) { throw unavailable(); }
            }
            var rows = jdbc.queryForList("SELECT d.* FROM crm_trial_login_delivery d JOIN crm_trial_account a ON a.application_id=d.application_id AND a.user_id=d.user_id WHERE d.application_id=? AND a.tenant_id=?",
                    id, app.policy().demoTenantId());
            if (rows.size() != 1 || rows.get(0).get("ciphertext") == null) { throw unavailable(); }
            Map<String, Object> row = rows.get(0);
            String claimHash = TrialServiceAuth.sha256(identity.idempotencyKey());
            Timestamp retryUntil = (Timestamp) row.get("retry_until");
            if (row.get("claim_hash") != null && (!claimHash.equals(row.get("claim_hash"))
                    || retryUntil == null || !retryUntil.toInstant().isAfter(Instant.now()))) { throw unavailable(); }
            long userId = ((Number) row.get("user_id")).longValue();
            var envelope = new TrialLoginVault.Envelope((String) row.get("encryption_key_id"), (String) row.get("nonce"), (String) row.get("ciphertext"));
            String password = vault.decrypt(binding(app, userId), envelope);
            tenants.validTenant(app.policy().demoTenantId());
            var user = TenantUtils.execute(app.policy().demoTenantId(), () -> users.getUser(userId));
            if (user == null || !Integer.valueOf(0).equals(user.getStatus()) || !users.isPasswordMatch(password, user.getPassword())) { throw unavailable(); }
            // Bounded retries only for the same authenticated delivery operation. A new card cannot reset/reissue this password.
            if (retryUntil == null) {
                Instant limit = Instant.now().plusSeconds(300).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                retryUntil = Timestamp.from(limit.isBefore(app.expiresAt()) ? limit : app.expiresAt());
                jdbc.update("UPDATE crm_trial_login_delivery SET claim_hash=?,retry_until=? WHERE application_id=?", claimHash, retryUntil, id);
            }
            return new Credential(user.getUsername(), password, app.policy().demoTenantId(), app.policy().mgsLoginUrl(),
                    app.expiresAt().toString(), retryUntil.toInstant().toString());
        });
    }
    public void revoke(String id) {
        jdbc.update("UPDATE crm_trial_login_delivery SET ciphertext=NULL,nonce=NULL WHERE application_id=?", id);
    }
    private static String binding(TrialStore.Application app, long userId) {
        return "mgs-trial-login-v1\n" + app.id() + "\n" + app.identityHash() + "\n" + app.policy().demoTenantId() + "\n" + userId;
    }
    private static cn.iocoder.yudao.framework.common.exception.ServiceException unavailable() {
        return TrialException.error(14, "账号未就绪、登录交付已失效或已被领取，请通过原安全会话核验");
    }
}
