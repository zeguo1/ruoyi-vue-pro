package cn.iocoder.yudao.module.crm.service.trial;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Repository
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
@RequiredArgsConstructor
public class TrialStore {
    public static final List<String> PROVISION_STEPS = List.of("CRM", "MGS", "DEMO", "KNOWDO_MEMBER", "MGS_GRANT", "KNOWDO_AUTH", "DELIVERY");
    public static final List<String> REVOKE_STEPS = List.of("MGS_REVOKE", "KNOWDO_REVOKE");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;

    public record Application(String id, String issuer, String subjectId, String identityHash, String requestHash,
                              String team, String contactName, String verifiedEmail, String scenario,
                              TrialProperties.Policy policy, String status, Instant confirmedAt, Instant expiresAt) { }
    public record Step(String name, String state, Map<String, String> result, String errorCode, int attempts) { }

    public Application submit(TrialIdentity identity, String key, String team, String contactName, String scenario,
                              TrialProperties.Policy policy, int maxApplications) {
        String hash = TrialServiceAuth.sha256(encode(List.of(team, contactName, identity.verifiedEmail(), scenario)));
        return transaction.execute(tx -> {
            // Serializes quota and first creation, including across application replicas.
            jdbc.queryForObject("SELECT id FROM crm_trial_guard WHERE id=1 FOR UPDATE", Integer.class);
            List<Application> existing = jdbc.query("SELECT * FROM crm_trial_application WHERE identity_hash=? OR (issuer=? AND idempotency_key=?)",
                    this::application, identity.identityHash(), identity.issuer(), key);
            if (!existing.isEmpty()) {
                Application old = existing.get(0);
                if (existing.size() != 1 || !old.identityHash().equals(identity.identityHash()) || !old.requestHash().equals(hash)) {
                    throw TrialException.conflict();
                }
                return old;
            }
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM crm_trial_application WHERE status <> 'EXPIRED'", Long.class);
            if (count != null && count >= maxApplications) { throw TrialException.quota(); }
            String id = UUID.randomUUID().toString();
            Instant now = Instant.now();
            jdbc.update("INSERT INTO crm_trial_application(id,issuer,subject_id,identity_hash,operator_tenant_id,idempotency_key,request_hash,team,contact_name,verified_email,scenario,source,policy_json,status,expires_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, identity.issuer(), identity.subjectId(), identity.identityHash(), policy.operatorTenantId(), key, hash, team, contactName,
                    identity.verifiedEmail(), scenario, "knowdo_website", encode(policy), "SUBMITTED",
                    Timestamp.from(now.plus(policy.durationDays(), ChronoUnit.DAYS)), Timestamp.from(now), Timestamp.from(now));
            for (String name : java.util.stream.Stream.concat(PROVISION_STEPS.stream(), REVOKE_STEPS.stream()).toList()) {
                jdbc.update("INSERT INTO crm_trial_step(application_id,step,state,updated_at) VALUES(?,?,'PENDING',?)", id, name, Timestamp.from(now));
            }
            return get(id);
        });
    }

    public Application owned(String id, TrialIdentity identity) {
        Application app = get(id);
        if (app == null || !app.identityHash().equals(identity.identityHash())) { throw TrialException.notFound(); }
        return app;
    }

    public Application get(String id) {
        List<Application> list = jdbc.query("SELECT * FROM crm_trial_application WHERE id=?", this::application, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public <T> T locked(String id, Function<Application, T> action) {
        return transaction.execute(tx -> {
            List<Application> rows = jdbc.query("SELECT * FROM crm_trial_application WHERE id=? FOR UPDATE", this::application, id);
            if (rows.isEmpty()) { throw TrialException.notFound(); }
            return action.apply(rows.get(0));
        });
    }

    public void confirm(String id, TrialIdentity identity) {
        locked(id, app -> {
            if (!app.identityHash().equals(identity.identityHash())) { throw TrialException.notFound(); }
            // Confirmation is an attestation by the trusted backend, bound to this request body by HMAC.
            if (identity.confirmation().isBlank()) { throw TrialException.unconfirmed(); }
            if (app.expiresAt().isAfter(Instant.now()) && app.confirmedAt() == null) {
                jdbc.update("UPDATE crm_trial_application SET confirmed_at=?,status='PROVISIONING',updated_at=? WHERE id=?",
                        now(), now(), id);
            }
            return null;
        });
    }

    public List<Step> steps(String id) {
        return jdbc.query("SELECT * FROM crm_trial_step WHERE application_id=? ORDER BY step", (rs, n) -> new Step(
                rs.getString("step"), rs.getString("state"), decodeMap(rs.getString("result_json")), rs.getString("error_code"), rs.getInt("attempts")), id);
    }
    public Step step(String id, String name) { return steps(id).stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow(); }

    public void localDone(String id, String name, Map<String, String> result) {
        jdbc.update("UPDATE crm_trial_step SET state='DONE',result_json=?,error_code=NULL,attempts=attempts+1,updated_at=? WHERE application_id=? AND step=?",
                encode(result), now(), id, name);
    }

    /** Claim only after taking the application lock. Caller must release its transaction before network I/O. */
    public String claim(String id, String name) {
        String lease = UUID.randomUUID().toString();
        int rows = jdbc.update("UPDATE crm_trial_step SET state='RUNNING',lease_id=?,lease_until=?,attempts=attempts+1,updated_at=? WHERE application_id=? AND step=? AND state<>'DONE' AND (lease_until IS NULL OR lease_until < ?)",
                lease, Timestamp.from(Instant.now().plusSeconds(60)), now(), id, name, now());
        return rows == 1 ? lease : null;
    }
    public boolean finish(String id, String name, String lease, Map<String, String> result) {
        return jdbc.update("UPDATE crm_trial_step SET state='DONE',result_json=?,error_code=NULL,lease_id=NULL,lease_until=NULL,updated_at=? WHERE application_id=? AND step=? AND lease_id=?",
                encode(result), now(), id, name, lease) == 1;
    }
    public void uncertain(String id, String name, String lease, String safeErrorCode) {
        jdbc.update("UPDATE crm_trial_step SET state='UNKNOWN',error_code=?,lease_id=NULL,lease_until=NULL,updated_at=? WHERE application_id=? AND step=? AND lease_id=?",
                safeErrorCode, now(), id, name, lease);
    }
    public void failed(String id, String name) {
        jdbc.update("UPDATE crm_trial_step SET state='FAILED',error_code='LOCAL_STEP_FAILED',updated_at=? WHERE application_id=? AND step=? AND state<>'DONE'",
                now(), id, name);
    }
    public void status(String id, String status) {
        jdbc.update("UPDATE crm_trial_application SET status=?,updated_at=? WHERE id=?", status, now(), id);
    }
    public void touch(String id) { jdbc.update("UPDATE crm_trial_application SET updated_at=? WHERE id=?", now(), id); }
    public List<Application> pending(int limit) {
        return jdbc.query("SELECT a.* FROM crm_trial_application a WHERE status<>'EXPIRED' AND (status<>'READY' OR expires_at<=?) AND (confirmed_at IS NOT NULL OR expires_at<=? OR EXISTS(SELECT 1 FROM crm_trial_step s WHERE s.application_id=a.id AND s.step='CRM' AND s.state<>'DONE')) ORDER BY updated_at LIMIT ?",
                this::application, now(), now(), limit);
    }
    private Application application(ResultSet rs, int row) throws SQLException {
        Timestamp confirmed = rs.getTimestamp("confirmed_at");
        return new Application(rs.getString("id"), rs.getString("issuer"), rs.getString("subject_id"), rs.getString("identity_hash"),
                rs.getString("request_hash"), rs.getString("team"), rs.getString("contact_name"), rs.getString("verified_email"),
                rs.getString("scenario"), decode(rs.getString("policy_json"), TrialProperties.Policy.class), rs.getString("status"),
                confirmed == null ? null : confirmed.toInstant(), rs.getTimestamp("expires_at").toInstant());
    }
    public String encode(Object object) {
        try { return json.writeValueAsString(object); } catch (JsonProcessingException e) { throw new IllegalStateException("Invalid trial record", e); }
    }
    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); } catch (JsonProcessingException e) { throw new IllegalStateException("Invalid trial record", e); }
    }
    @SuppressWarnings("unchecked")
    private Map<String, String> decodeMap(String value) { return value == null ? Map.of() : decode(value, Map.class); }
    private static Timestamp now() { return Timestamp.from(Instant.now()); }
}
