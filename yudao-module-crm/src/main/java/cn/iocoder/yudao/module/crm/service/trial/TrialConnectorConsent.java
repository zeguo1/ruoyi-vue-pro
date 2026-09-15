package cn.iocoder.yudao.module.crm.service.trial;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.Instant;

@Service @RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
public class TrialConnectorConsent {
    private final TrialStore store;
    private final TrialProperties properties;
    private final JdbcTemplate jdbc;

    /** Called only with the CONSENT service capability after the user's safe-card action. */
    public void record(String applicationId, TrialIdentity identity) {
        properties.newPolicy();
        store.locked(applicationId, app -> {
            if (!app.identityHash().equals(identity.identityHash())) throw TrialException.notFound();
            if (!app.expiresAt().isAfter(Instant.now())
                    || jdbc.queryForObject("SELECT COUNT(*) FROM crm_trial_verified_contact WHERE application_id=?", Integer.class, app.id()) != 1) {
                throw TrialException.unconfirmed();
            }
            String action = TrialServiceAuth.sha256(identity.idempotencyKey());
            var existing = jdbc.queryForList("SELECT action_hash FROM crm_trial_connector_consent WHERE application_id=?", app.id());
            if (!existing.isEmpty() && action.equals(existing.get(0).get("action_hash"))) return null; // Retry cannot extend consent.
            // Use the first authenticated receipt time, including when an older action is replayed after a newer one.
            Instant now = jdbc.queryForObject("SELECT created_at FROM crm_trial_connector_operation WHERE issuer=? AND operation_hash=?",
                    Timestamp.class, identity.issuer(), identity.idempotencyKey().substring("knowdo-".length())).toInstant();
            Instant expires = now.plusSeconds(300);
            if (!expires.isAfter(Instant.now())) throw TrialException.unconfirmed();
            if (app.expiresAt().isBefore(expires)) expires = app.expiresAt();
            if (existing.isEmpty()) {
                jdbc.update("INSERT INTO crm_trial_connector_consent(application_id,identity_hash,action_hash,confirmed_at,expires_at) VALUES(?,?,?,?,?)",
                        app.id(), identity.identityHash(), action, Timestamp.from(now), Timestamp.from(expires));
            } else {
                jdbc.update("UPDATE crm_trial_connector_consent SET identity_hash=?,action_hash=?,confirmed_at=?,expires_at=? WHERE application_id=?",
                        identity.identityHash(), action, Timestamp.from(now), Timestamp.from(expires), app.id());
            }
            return null;
        });
    }

    /** Called only by the Agent command. Atomically checks consent before releasing provisioning. */
    public TrialIdentity activate(String applicationId, TrialIdentity identity) {
        properties.newPolicy();
        return store.locked(applicationId, app -> {
            if (!app.identityHash().equals(identity.identityHash())) throw TrialException.notFound();
            if (!app.expiresAt().isAfter(Instant.now())) throw TrialException.unconfirmed();
            var confirmed = new TrialIdentity(identity.issuer(), identity.subjectId(), "", "mgs-persisted-confirmation", identity.idempotencyKey(), "");
            if (app.confirmedAt() == null) {
                var rows = jdbc.queryForList("SELECT identity_hash,expires_at FROM crm_trial_connector_consent WHERE application_id=?", app.id());
                if (rows.size() != 1 || !identity.identityHash().equals(rows.get(0).get("identity_hash"))
                        || !((Timestamp) rows.get(0).get("expires_at")).toInstant().isAfter(Instant.now())) {
                    throw TrialException.unconfirmed();
                }
                store.confirm(app.id(), confirmed);
            }
            return confirmed;
        });
    }
}
