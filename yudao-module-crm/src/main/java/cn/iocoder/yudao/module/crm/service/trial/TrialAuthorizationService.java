package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
@RequiredArgsConstructor
public class TrialAuthorizationService {
    private final TrialStore store;
    private final JdbcTemplate jdbc;
    private final TrialOAuthGateway oauth;

    public Map<String, String> prepare(TrialStore.Application app) {
        return store.locked(app.id(), current -> {
            requireEligible(current);
            var rows = rows(app.id());
            if (!rows.isEmpty()) { return Map.of("mgsAuthorizationRef", (String) rows.get(0).get("authorization_ref")); }
            long userId = Long.parseLong(store.step(app.id(), "MGS").result().get("userId"));
            long refreshId = TenantUtils.execute(app.policy().demoTenantId(), () -> oauth.create(userId, app.policy().oauthClientId()));
            String reference = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO crm_trial_authorization(application_id,authorization_ref,refresh_token_id,created_at) VALUES(?,?,?,?)",
                    app.id(), reference, refreshId, Timestamp.from(Instant.now()));
            return Map.of("mgsAuthorizationRef", reference);
        });
    }

    /** An explicit credential exchange, never called from a model-visible status/guide method. */
    public TrialOAuthGateway.Credential exchange(String applicationId, TrialIdentity identity) {
        return store.locked(applicationId, app -> {
            store.owned(app.id(), identity);
            requireEligible(app);
            var rows = rows(app.id());
            if (rows.isEmpty() || !"DONE".equals(store.step(app.id(), "MGS_GRANT").state())) { throw TrialException.error(11, "个人授权尚未准备完成"); }
            long refreshId = ((Number) rows.get(0).get("refresh_token_id")).longValue();
            long userId = Long.parseLong(store.step(app.id(), "MGS").result().get("userId"));
            var credential = TenantUtils.execute(app.policy().demoTenantId(), () -> oauth.resolve(userId, refreshId, app.policy().oauthClientId()));
            Instant tokenExpiry = Instant.parse(credential.expiresAt());
            return new TrialOAuthGateway.Credential(credential.accessToken(),
                    (tokenExpiry.isBefore(app.expiresAt()) ? tokenExpiry : app.expiresAt()).toString());
        });
    }

    private void requireEligible(TrialStore.Application app) {
        if (app.confirmedAt() == null || !app.expiresAt().isAfter(Instant.now())
                || !("PROVISIONING".equals(app.status()) || "READY".equals(app.status()))
                || app.policy().oauthClientId() == null || app.policy().oauthClientId().isBlank()) {
            throw TrialException.error(11, "申请未确认、未就绪或已到期，不能领取授权");
        }
        for (String step : List.of("MGS", "DEMO", "KNOWDO_MEMBER")) {
            if (!"DONE".equals(store.step(app.id(), step).state())) { throw TrialException.error(11, "账号准备步骤尚未完成"); }
        }
    }
    private List<Map<String, Object>> rows(String id) {
        return jdbc.queryForList("SELECT authorization_ref,refresh_token_id FROM crm_trial_authorization WHERE application_id=?", id);
    }
}
