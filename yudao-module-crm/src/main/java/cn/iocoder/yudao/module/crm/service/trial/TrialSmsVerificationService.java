package cn.iocoder.yudao.module.crm.service.trial;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
public class TrialSmsVerificationService {
    private final TrialProperties properties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final TrialSmsSender sender;
    private final TrialStore store;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record SendResult(String challengeId, String state, String expiresAt, int resendAfterSeconds) { }
    public record VerificationResult(String verificationToken, String expiresAt) {
        @Override public String toString() { return "VerificationResult[REDACTED]"; }
    }

    public SendResult send(TrialIdentity identity, String mobile) {
        var config = config(); validKey(identity.idempotencyKey());
        if (mobile == null || !mobile.matches("1[3-9][0-9]{9}")) { throw invalid(); }
        String id = UUID.randomUUID().toString();
        String code = String.format(java.util.Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        String keyHash = TrialServiceAuth.sha256(identity.idempotencyKey());
        String reserved = transaction.execute(tx -> {
            jdbc.queryForObject("SELECT id FROM crm_trial_guard WHERE id=1 FOR UPDATE", Integer.class);
            var old = jdbc.queryForList("SELECT * FROM crm_trial_sms_challenge WHERE issuer=? AND send_key_hash=?", identity.issuer(), keyHash);
            if (!old.isEmpty()) {
                var row = old.get(0);
                if (!identity.identityHash().equals(row.get("identity_hash")) || !mobile.equals(row.get("mobile"))) { throw TrialException.conflict(); }
                return (String) row.get("id"); // Never retransmit an uncertain or already sent request.
            }
            Instant now = Instant.now();
            if (count("SELECT COUNT(*) FROM crm_trial_sms_challenge WHERE (identity_hash=? OR mobile=?) AND created_at>?",
                    identity.identityHash(), mobile, at(now.minusSeconds(config.getResendSeconds()))) > 0
                    || count("SELECT COUNT(*) FROM crm_trial_sms_challenge WHERE mobile=? AND created_at>?", mobile, at(now.minusSeconds(86400))) >= config.getMaxPerMobilePerDay()
                    || count("SELECT COUNT(*) FROM crm_trial_sms_challenge WHERE identity_hash=? AND created_at>?", identity.identityHash(), at(now.minusSeconds(86400))) >= config.getMaxPerIdentityPerDay()
                    || count("SELECT COUNT(*) FROM crm_trial_sms_challenge WHERE created_at>?", at(now.minusSeconds(86400))) >= config.getMaxTotalPerDay()) {
                throw TrialException.error(15, "短信获取过于频繁或已达限额，请稍后再试");
            }
            jdbc.update("UPDATE crm_trial_sms_challenge SET state='SUPERSEDED' WHERE identity_hash=? AND application_id IS NULL AND state IN ('SENDING','SENT','VERIFIED')", identity.identityHash());
            jdbc.update("INSERT INTO crm_trial_sms_challenge(id,issuer,identity_hash,mobile,send_key_hash,code_hash,state,max_attempts,expires_at,created_at) VALUES(?,?,?,?,?,?,'SENDING',?,?,?)",
                    id, identity.issuer(), identity.identityHash(), mobile, keyHash, codeHash(id, code), config.getMaxAttempts(),
                    at(now.plusSeconds(config.getCodeTtlSeconds())), at(now));
            return id;
        });
        if (id.equals(reserved)) {
            String state = "SENT";
            try { sender.send(mobile, code, config.getTemplateCode()); }
            catch (RuntimeException exception) { state = "UNKNOWN"; }
            // No DB transaction remains open during the provider request. A concurrent resend may supersede this challenge.
            jdbc.update("UPDATE crm_trial_sms_challenge SET state=? WHERE id=? AND state='SENDING'", state, id);
        }
        var row = jdbc.queryForMap("SELECT * FROM crm_trial_sms_challenge WHERE id=?", reserved);
        return new SendResult(reserved, (String) row.get("state"), time(row, "expires_at").toString(), config.getResendSeconds());
    }

    public VerificationResult verify(TrialIdentity identity, String challengeId, String code) {
        var config = config(); validKey(identity.idempotencyKey());
        if (code == null || !code.matches("[0-9]{6}") || challengeId == null || !challengeId.matches("[a-f0-9-]{36}")) { throw invalid(); }
        String verifyKey = TrialServiceAuth.sha256(identity.idempotencyKey());
        VerificationResult result = transaction.execute(tx -> {
            var rows = jdbc.queryForList("SELECT * FROM crm_trial_sms_challenge WHERE id=? FOR UPDATE", challengeId);
            if (rows.isEmpty()) { throw invalid(); }
            var row = rows.get(0);
            if (!identity.identityHash().equals(row.get("identity_hash"))) { throw invalid(); }
            if (((Number) row.get("attempts")).intValue() >= ((Number) row.get("max_attempts")).intValue()) { throw invalid(); }
            if ("VERIFIED".equals(row.get("state"))) {
                // Same confirmed safe-card action can recover a lost response, but cannot obtain a second grant.
                if (!verifyKey.equals(row.get("verify_key_hash")) || !time(row, "proof_expires_at").isAfter(Instant.now())) { throw invalid(); }
                if (!constant((String) row.get("code_hash"), codeHash(challengeId, code))) {
                    jdbc.update("UPDATE crm_trial_sms_challenge SET attempts=attempts+1 WHERE id=?", challengeId);
                    return null;
                }
                String token = proof(challengeId, verifyKey);
                if (!TrialServiceAuth.sha256(token).equals(row.get("proof_hash"))) { throw invalid(); }
                return new VerificationResult(token, time(row, "proof_expires_at").toString());
            }
            if (!"SENT".equals(row.get("state")) || !time(row, "expires_at").isAfter(Instant.now())
                    || ((Number) row.get("attempts")).intValue() >= ((Number) row.get("max_attempts")).intValue()) { throw invalid(); }
            if (!constant((String) row.get("code_hash"), codeHash(challengeId, code))) {
                jdbc.update("UPDATE crm_trial_sms_challenge SET attempts=attempts+1 WHERE id=?", challengeId);
                return null; // Commit the failed-attempt count BEFORE returning the business error.
            }
            String token = proof(challengeId, verifyKey);
            Instant expires = Instant.now().plusSeconds(config.getProofTtlSeconds()).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            jdbc.update("UPDATE crm_trial_sms_challenge SET state='VERIFIED',verify_key_hash=?,proof_hash=?,proof_expires_at=?,verified_at=? WHERE id=?",
                    verifyKey, TrialServiceAuth.sha256(token), at(expires), at(Instant.now()), challengeId);
            return new VerificationResult(token, expires.toString());
        });
        if (result == null) { throw invalid(); }
        return result;
    }

    /** Bind proof, application and trusted phone atomically. No confirmation or account creation occurs here. */
    public TrialStore.Application submit(TrialIdentity identity, String team, String contactName, String scenario) {
        config(); validKey(identity.idempotencyKey());
        if (identity.verificationToken() == null || !identity.verificationToken().matches("[a-f0-9]{64}")) { throw invalid(); }
        return transaction.execute(tx -> {
            // Keep lock order identical to send/TrialStore.submit: quota guard before challenge/application.
            jdbc.queryForObject("SELECT id FROM crm_trial_guard WHERE id=1 FOR UPDATE", Integer.class);
            var rows = jdbc.queryForList("SELECT * FROM crm_trial_sms_challenge WHERE proof_hash=? FOR UPDATE", TrialServiceAuth.sha256(identity.verificationToken()));
            if (rows.isEmpty()) { throw invalid(); }
            var row = rows.get(0);
            if (!identity.identityHash().equals(row.get("identity_hash")) || !"VERIFIED".equals(row.get("state"))
                    || !time(row, "proof_expires_at").isAfter(Instant.now())) { throw invalid(); }
            // Email/mobile values asserted by the caller are never accepted as verified contact information.
            var trusted = new TrialIdentity(identity.issuer(), identity.subjectId(), "", identity.confirmation(), identity.idempotencyKey(), identity.verificationToken());
            var app = store.submit(trusted, trusted.idempotencyKey(), team, contactName, scenario, properties.newPolicy(), properties.getMaxApplications());
            if (row.get("application_id") != null && !app.id().equals(row.get("application_id"))) { throw invalid(); }
            var contacts = jdbc.queryForList("SELECT mobile FROM crm_trial_verified_contact WHERE application_id=?", app.id());
            if (!contacts.isEmpty() && !contacts.get(0).get("mobile").equals(row.get("mobile"))) { throw TrialException.conflict(); }
            if (contacts.isEmpty()) {
                jdbc.update("INSERT INTO crm_trial_verified_contact(application_id,challenge_id,mobile,verified_at) VALUES(?,?,?,?)",
                        app.id(), row.get("id"), row.get("mobile"), at(time(row, "verified_at")));
            }
            jdbc.update("UPDATE crm_trial_sms_challenge SET application_id=? WHERE id=?", app.id(), row.get("id"));
            return app;
        });
    }

    private TrialProperties.SmsVerification config() {
        properties.newPolicy();
        var config = properties.getSmsVerification();
        if (config == null || !config.ready()) { throw TrialException.unavailable(); }
        return config;
    }
    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private String codeHash(String id, String code) { return TrialServiceAuth.hmac(properties.getSmsVerification().getSecret(), "sms-code\n" + id + "\n" + code); }
    private String proof(String id, String key) { return TrialServiceAuth.hmac(properties.getSmsVerification().getSecret(), "sms-proof\n" + id + "\n" + key); }
    private static boolean constant(String first, String second) { return MessageDigest.isEqual(first.getBytes(java.nio.charset.StandardCharsets.US_ASCII), second.getBytes(java.nio.charset.StandardCharsets.US_ASCII)); }
    private static Timestamp at(Instant instant) { return Timestamp.from(instant); }
    private static Instant time(Map<String, Object> row, String key) { return ((Timestamp) row.get(key)).toInstant(); }
    private static void validKey(String key) { if (key == null || !key.matches("[a-zA-Z0-9_.:-]{16,128}")) { throw invalid(); } }
    private static cn.iocoder.yudao.framework.common.exception.ServiceException invalid() { return TrialException.error(16, "短信验证无效、已过期或已达尝试上限，请重新验证"); }
}
