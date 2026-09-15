package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class TrialSmsVerificationTest {
    JdbcTemplate jdbc;
    TrialStore store;
    TrialProperties properties;
    TrialSmsVerificationService verification;
    CapturingSender sender;
    TrialIdentity identity = identity("person", "send-action-00000001", "");

    static class CapturingSender implements TrialSmsSender {
        final Map<String, String> codes = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        boolean fail;
        @Override public void send(String mobile, String code, String template) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            calls.incrementAndGet(); codes.put(mobile, code);
            if (fail) { throw new IllegalStateException("fixture provider timeout"); }
        }
    }
    static TrialProperties properties() {
        var p = new TrialProperties();
        p.setEnabled(true); p.setEnvironment("isolated-sms"); p.setOperatorTenantId(8L); p.setOwnerUserId(9L);
        p.setDemoTenantId(1L); p.setDurationDays(7); p.setMaxApplications(20); p.setOauthClientId("fixture-client");
        p.setKnowdoBaseUrl("https://knowdo.example.invalid"); p.setMgsLoginUrl("https://mgs.example.invalid");
        p.setOutboundKeyId("fixture"); p.setOutboundSecret("fixture-outbound-secret-at-least-32-chars");
        p.getLoginDelivery().setActiveKeyId("fixture");
        p.getLoginDelivery().setEncryptionKeys(Map.of("fixture", java.util.Base64.getEncoder().encodeToString(new byte[32])));
        p.getSmsVerification().setEnabled(true); p.getSmsVerification().setTemplateCode("fixture-code");
        p.getSmsVerification().setSecret("fixture-sms-secret-separated-from-service-signing");
        return p;
    }
    static TrialIdentity identity(String subject, String key, String proof) { return new TrialIdentity("fixture-knowdo", subject, "", "", key, proof); }
    @BeforeEach void setup() {
        var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        new ResourceDatabasePopulator(new FileSystemResource("../script/trial/V20260914_01__trial_onboarding.sql"),
                new FileSystemResource("../script/trial/V20260915_05__trial_sms_verification.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds); var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        store = new TrialStore(jdbc, tx, new ObjectMapper()); properties = properties(); sender = new CapturingSender();
        verification = new TrialSmsVerificationService(properties, jdbc, tx, sender, store);
    }
    String send() { return verification.send(identity, "13800000001").challengeId(); }
    String verify(String id) { return verification.verify(identity, id, sender.codes.get("13800000001")).verificationToken(); }
    TrialStore.Application submit(String proof) { return verification.submit(identity("person", "submit-action-00001", proof), "示例团队", "示例联系人", "CRM_FOLLOW_UP"); }

    @Test void verificationDoesNotApplyOrConfirmAndProofRetainsServerVerifiedPhone() {
        String challenge = send(); String code = sender.codes.get("13800000001"); String proof = verify(challenge);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM crm_trial_application", Integer.class));
        assertNotEquals(code, jdbc.queryForObject("SELECT code_hash FROM crm_trial_sms_challenge", String.class));
        assertNotEquals(proof, jdbc.queryForObject("SELECT proof_hash FROM crm_trial_sms_challenge", String.class));
        var app = submit(proof); assertEquals("SUBMITTED", app.status()); assertNull(app.confirmedAt());
        assertEquals("", app.verifiedEmail());
        assertEquals("13800000001", jdbc.queryForObject("SELECT mobile FROM crm_trial_verified_contact WHERE application_id=?", String.class, app.id()));
        assertEquals(jdbc.queryForObject("SELECT verified_at FROM crm_trial_sms_challenge WHERE id=?", Timestamp.class, challenge),
                jdbc.queryForObject("SELECT verified_at FROM crm_trial_verified_contact WHERE application_id=?", Timestamp.class, app.id()));
        assertEquals(app.id(), submit(proof).id());
        assertThrows(ServiceException.class, () -> store.confirm(app.id(), identity));
        assertEquals("PENDING", store.step(app.id(), "MGS").state());
    }

    @Test void failedAttemptsCommitAndPreventCorrectCodeAfterLimit() {
        String id = send(); String wrong = sender.codes.get("13800000001").equals("000000") ? "000001" : "000000";
        for (int i = 0; i < 5; i++) { assertThrows(ServiceException.class, () -> verification.verify(identity, id, wrong)); }
        assertEquals(5, jdbc.queryForObject("SELECT attempts FROM crm_trial_sms_challenge", Integer.class));
        assertThrows(ServiceException.class, () -> verify(id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM crm_trial_sms_challenge WHERE proof_hash IS NOT NULL", Integer.class));
    }

    @Test void proofAndChallengeCannotMoveToAnotherSubjectOrVerificationAction() {
        String id = send(); String code = sender.codes.get("13800000001");
        var other = identity("other", "other-action-000001", "");
        assertThrows(ServiceException.class, () -> verification.verify(other, id, code));
        String proof = verify(id); assertEquals(proof, verify(id));
        assertThrows(ServiceException.class, () -> verification.verify(identity("person", "another-action-0001", ""), id, code));
        assertThrows(ServiceException.class, () -> verification.submit(identity("other", "submit-action-00002", proof), "团队", "联系人", "CRM_FOLLOW_UP"));
        assertThrows(ServiceException.class, () -> submit("f".repeat(64)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM crm_trial_application", Integer.class));
    }

    @Test void codeAndProofExpiryAreEnforced() {
        String id = send();
        jdbc.update("UPDATE crm_trial_sms_challenge SET expires_at=?", Timestamp.from(Instant.now().minusSeconds(1)));
        assertThrows(ServiceException.class, () -> verify(id));
        jdbc.update("UPDATE crm_trial_sms_challenge SET expires_at=?", Timestamp.from(Instant.now().plusSeconds(60)));
        String proof = verify(id);
        jdbc.update("UPDATE crm_trial_sms_challenge SET proof_expires_at=?", Timestamp.from(Instant.now().minusSeconds(1)));
        assertThrows(ServiceException.class, () -> verify(id)); assertThrows(ServiceException.class, () -> submit(proof));
    }

    @Test void concurrentSendAndVerificationAreIdempotent() throws Exception {
        var pool = Executors.newFixedThreadPool(6);
        try {
            var calls = java.util.stream.IntStream.range(0, 12).mapToObj(i -> (Callable<String>) this::send).toList();
            var ids = pool.invokeAll(calls); String id = ids.get(0).get();
            for (var result : ids) { assertEquals(id, result.get()); }
            assertEquals(1, sender.calls.get());
            var proofs = pool.invokeAll(java.util.stream.IntStream.range(0, 12).mapToObj(i -> (Callable<String>) () -> verify(id)).toList());
            String proof = proofs.get(0).get(); for (var result : proofs) { assertEquals(proof, result.get()); }
            var apps = pool.invokeAll(java.util.stream.IntStream.range(0, 12).mapToObj(i -> (Callable<String>) () -> submit(proof).id()).toList());
            String app = apps.get(0).get(); for (var result : apps) { assertEquals(app, result.get()); }
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM crm_trial_verified_contact", Integer.class));
        } finally { pool.shutdownNow(); }
    }

    @Test void providerUncertaintyNeverCausesAutomaticSmsRetransmission() {
        sender.fail = true; var result = verification.send(identity, "13800000001"); assertEquals("UNKNOWN", result.state());
        sender.fail = false; assertEquals("UNKNOWN", verification.send(identity, "13800000001").state());
        assertEquals(1, sender.calls.get()); assertThrows(ServiceException.class, () -> verify(result.challengeId()));
    }

    @Test void cooldownLimitsAndResendingInvalidateOldUnboundProof() {
        String id = send(); String proof = verify(id);
        var next = identity("person", "send-action-00000002", "");
        assertThrows(ServiceException.class, () -> verification.send(next, "13800000002"));
        assertThrows(ServiceException.class, () -> verification.send(identity("other", "other-action-000001", ""), "13800000001"));
        jdbc.update("UPDATE crm_trial_sms_challenge SET created_at=?", Timestamp.from(Instant.now().minusSeconds(61)));
        String nextId = verification.send(next, "13800000002").challengeId(); assertNotEquals(id, nextId);
        assertThrows(ServiceException.class, () -> submit(proof));
        properties.getSmsVerification().setMaxPerMobilePerDay(1);
        jdbc.update("UPDATE crm_trial_sms_challenge SET created_at=?", Timestamp.from(Instant.now().minusSeconds(61)));
        assertThrows(ServiceException.class, () -> verification.send(identity("another", "send-action-00000003", ""), "13800000002"));
    }

    @Test void samePhoneDoesNotMergeDistinctTrustedSubjects() {
        String firstProof = verify(send()); String firstApp = submit(firstProof).id();
        jdbc.update("UPDATE crm_trial_sms_challenge SET created_at=?", Timestamp.from(Instant.now().minusSeconds(61)));
        var other = identity("other", "other-action-000001", "");
        String id = verification.send(other, "13800000001").challengeId();
        String proof = verification.verify(other, id, sender.codes.get("13800000001")).verificationToken();
        var app = verification.submit(identity("other", "other-submit-000001", proof), "团队", "联系人", "CRM_FOLLOW_UP");
        assertNotEquals(firstApp, app.id()); assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM crm_trial_application", Integer.class));
    }

    @Test void contactFailureRollsBackApplicationAndLeavesProofReusable() {
        String proof = verify(send());
        jdbc.execute("ALTER TABLE crm_trial_verified_contact ADD CONSTRAINT test_fault CHECK (mobile='invalid')");
        assertThrows(RuntimeException.class, () -> submit(proof));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM crm_trial_application", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM crm_trial_step", Integer.class));
        assertNull(jdbc.queryForObject("SELECT application_id FROM crm_trial_sms_challenge", String.class));
        jdbc.execute("ALTER TABLE crm_trial_verified_contact DROP CONSTRAINT test_fault"); assertNotNull(submit(proof));
    }

    @Test void missingConfigurationAndDisabledEnrollmentCannotSendOrRedeem() {
        properties.getSmsVerification().setEnabled(false); assertThrows(ServiceException.class, this::send); assertEquals(0, sender.calls.get());
        properties.getSmsVerification().setEnabled(true); String proof = verify(send());
        properties.setEnabled(false); assertThrows(ServiceException.class, () -> submit(proof));
    }
    @Test void verifiedActionRecoveryCannotBypassFailedAttemptLimit() {
        String id = send(); verify(id);
        String wrong = sender.codes.get("13800000001").equals("000000") ? "000001" : "000000";
        for (int i = 0; i < 5; i++) { assertThrows(ServiceException.class, () -> verification.verify(identity, id, wrong)); }
        assertEquals(5, jdbc.queryForObject("SELECT attempts FROM crm_trial_sms_challenge", Integer.class));
        assertThrows(ServiceException.class, () -> verify(id));
    }
}
