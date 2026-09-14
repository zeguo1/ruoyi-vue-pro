package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit local H2 + deterministic KnowDo substitute. This is not a real KnowDo/WeChat integration test. */
class TrialOrchestratorTest {
    JdbcTemplate jdbc;
    TrialStore store;
    FakeKnowdo remote;
    TrialLocalProvisioner local;
    TrialOrchestrator orchestrator;
    final TrialProperties.Policy policy = new TrialProperties.Policy("test-demo", 8, 9, 1, 7, "https://demo.example.invalid", "https://knowdo.example.invalid", "test-trial-client");
    final TrialIdentity identity = new TrialIdentity("knowdo", "verified-subject-1", "trial@example.invalid", "confirmed-receipt", "application-request-0001");

    @BeforeEach
    void setup() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        new ResourceDatabasePopulator(new FileSystemResource("../script/trial/V20260914_01__trial_onboarding.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE test_effect (application_id VARCHAR(36), step VARCHAR(32), PRIMARY KEY(application_id, step))");
        store = new TrialStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)), new ObjectMapper());
        remote = new FakeKnowdo();
        local = (app, step, resources) -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            jdbc.update("INSERT INTO test_effect VALUES(?,?)", app.id(), step);
            return switch (step) {
                case "CRM" -> Map.of("clueId", "11");
                case "MGS" -> Map.of("tenantId", "1", "userId", "100", "roleId", "20");
                case "DEMO" -> Map.of("customerId", "55");
                case "MGS_GRANT" -> Map.of("mgsAuthorizationRef", "test-grant-reference");
                default -> Map.of("revoked", "true");
            };
        };
        orchestrator = new TrialOrchestrator(store, local, remote);
    }
    TrialStore.Application submit() { return store.submit(identity, identity.idempotencyKey(), "示例团队", "体验人", "CRM_FOLLOW_UP", policy, 30); }
    void confirm(String id) { store.confirm(id, identity); }

    @Test void firstApplicationRequiresConfirmationAndThenBecomesReady() {
        var app = submit();
        orchestrator.advance(app.id());
        assertEquals("SUBMITTED", store.get(app.id()).status());
        assertEquals("DONE", store.step(app.id(), "CRM").state());
        assertEquals("PENDING", store.step(app.id(), "MGS").state());
        assertEquals(0, remote.writes);
        confirm(app.id()); orchestrator.advance(app.id());
        assertEquals("READY", store.get(app.id()).status());
        assertEquals(3, remote.writes);
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM test_effect", Integer.class));
        orchestrator.advance(app.id());
        assertEquals(3, remote.writes);
    }

    @Test void concurrentSubmitAndCreateDoNotDuplicateEffects() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> calls = java.util.stream.IntStream.range(0, 16)
                    .mapToObj(i -> (Callable<String>) () -> submit().id()).toList();
            var futures = pool.invokeAll(calls);
            String id = futures.get(0).get();
            for (var f : futures) { assertEquals(id, f.get()); }
            confirm(id);
            pool.invokeAll(java.util.stream.IntStream.range(0, 16).mapToObj(i -> (Callable<Void>) () -> {
                orchestrator.advance(id); return null;
            }).toList()).forEach(f -> { try { f.get(); } catch (Exception e) { throw new RuntimeException(e); } });
            orchestrator.advance(id);
            assertEquals("READY", store.get(id).status());
            assertEquals(3, remote.writes);
            assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM test_effect", Integer.class));
        } finally { pool.shutdownNow(); }
    }

    @Test void identityAndIdempotencyCannotBeTransferred() {
        var app = submit();
        var other = new TrialIdentity("knowdo", "other", identity.verifiedEmail(), "confirmed", identity.idempotencyKey());
        assertThrows(ServiceException.class, () -> store.owned(app.id(), other));
        assertThrows(ServiceException.class, () -> store.confirm(app.id(), other));
        assertThrows(ServiceException.class, () -> store.submit(other, identity.idempotencyKey(), "示例团队", "体验人", "CRM_FOLLOW_UP", policy, 30));
        assertThrows(ServiceException.class, () -> store.submit(identity, identity.idempotencyKey(), "改名", "体验人", "CRM_FOLLOW_UP", policy, 30));
        assertThrows(ServiceException.class, () -> store.confirm(app.id(), new TrialIdentity("knowdo", identity.subjectId(), identity.verifiedEmail(), "", "another-key")));
        // Matching verified contact alone does not merge two distinct trusted subjects.
        var second = store.submit(other, "separate-idempotency-key", "示例团队", "体验人", "CRM_FOLLOW_UP", policy, 30);
        assertNotEquals(app.id(), second.id());
    }

    @Test void externalTimeoutIsReconciledAfterRestartWithoutRecreating() {
        var app = submit(); confirm(app.id()); remote.timeoutAfterWrite = true;
        orchestrator.advance(app.id());
        assertEquals("UNKNOWN", store.step(app.id(), "KNOWDO_MEMBER").state());
        assertEquals("DONE", store.step(app.id(), "MGS").state());
        assertEquals(1, remote.writes);
        // Read-only status has no side effects.
        store.owned(app.id(), identity); store.steps(app.id());
        assertEquals(1, remote.writes);
        new TrialOrchestrator(store, local, remote).advance(app.id());
        assertEquals("READY", store.get(app.id()).status());
        assertEquals(3, remote.writes);
    }

    @Test void transportFailureOnLookupNeverCreatesAnythingRemotely() {
        var app = submit(); confirm(app.id()); remote.lookupUnavailable = true;
        orchestrator.advance(app.id());
        assertEquals(0, remote.writes);
        assertEquals("UNKNOWN", store.step(app.id(), "KNOWDO_MEMBER").state());
        assertEquals("PROVISIONING", store.get(app.id()).status());
    }

    @Test void localFailureRollsBackEffectsAndCanResume() {
        var app = submit(); confirm(app.id()); AtomicBoolean once = new AtomicBoolean(true);
        var failing = new TrialOrchestrator(store, (a, step, ids) -> {
            var result = local.execute(a, step, ids);
            if (step.equals("MGS") && once.getAndSet(false)) { throw new IllegalStateException("test fault after insert"); }
            return result;
        }, remote);
        failing.advance(app.id());
        assertEquals("FAILED", store.step(app.id(), "MGS").state());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM test_effect WHERE step='MGS'", Integer.class));
        failing.advance(app.id());
        assertEquals("READY", store.get(app.id()).status());
    }

    @Test void expiryTracksBothSidesAndRetriesOnlyIncompleteSide() {
        var app = submit(); confirm(app.id()); orchestrator.advance(app.id());
        jdbc.update("UPDATE crm_trial_application SET expires_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), app.id());
        remote.revokeUnavailable = true;
        orchestrator.advance(app.id());
        assertEquals("REVOKING", store.get(app.id()).status());
        assertEquals("DONE", store.step(app.id(), "MGS_REVOKE").state());
        assertEquals("UNKNOWN", store.step(app.id(), "KNOWDO_REVOKE").state());
        remote.revokeUnavailable = false;
        new TrialOrchestrator(store, local, remote).advance(app.id());
        assertEquals("EXPIRED", store.get(app.id()).status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM test_effect WHERE step='MGS_REVOKE'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM crm_trial_application", Integer.class));
    }

    @Test void localRevocationFailureDoesNotPreventKnowdoRevocation() {
        var app = submit(); confirm(app.id()); orchestrator.advance(app.id());
        jdbc.update("UPDATE crm_trial_application SET expires_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), app.id());
        new TrialOrchestrator(store, (a, step, ids) -> {
            if ("MGS_REVOKE".equals(step)) { throw new IllegalStateException("test local outage"); }
            return local.execute(a, step, ids);
        }, remote).advance(app.id());
        assertEquals("REVOKING", store.get(app.id()).status());
        assertEquals("DONE", store.step(app.id(), "KNOWDO_REVOKE").state());
    }

    @Test void sharedTenantAccountsCannotUseGeneralApisOrWrongTenant() {
        var first = submit(); confirm(first.id()); orchestrator.advance(first.id());
        var secondIdentity = new TrialIdentity("knowdo", "subject-2", "second@example.invalid", "confirmed", "second-request-0002");
        var second = store.submit(secondIdentity, secondIdentity.idempotencyKey(), "另一个团队", "用户2", "CRM_FOLLOW_UP", policy, 30);
        store.confirm(second.id(), secondIdentity); orchestrator.advance(second.id());
        jdbc.update("INSERT INTO crm_trial_account(application_id,tenant_id,user_id,role_id) VALUES(?,?,?,?)", first.id(), 1, 100, 20);
        jdbc.update("INSERT INTO crm_trial_account(application_id,tenant_id,user_id,role_id) VALUES(?,?,?,?)", second.id(), 1, 101, 21);
        var access = new cn.iocoder.yudao.module.crm.framework.trial.TrialBusinessAccess(jdbc, store);
        assertEquals(first.id(), access.applicationForUser(100L));
        assertEquals(second.id(), access.applicationForUser(101L));
        access.checkRoute(100L, 1L, "GET", "/admin-api/crm/trial-business/customer");
        for (String path : List.of("/admin-api/crm/customer/page", "/admin-api/crm/customer/export-excel",
                "/admin-api/infra/file/get", "/admin-api/system/permission/assign-user-role", "/admin-api/crm/clue/page")) {
            assertThrows(ServiceException.class, () -> access.checkRoute(100L, 1L, "GET", path));
        }
        assertThrows(ServiceException.class, () -> access.checkRoute(100L, 8L, "GET", "/admin-api/crm/trial-business/customer"));
        // Existing non-trial corporate users keep their existing permissions and API behavior.
        access.checkRoute(999L, 8L, "GET", "/admin-api/crm/customer/page");
        jdbc.update("UPDATE crm_trial_application SET expires_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), first.id());
        assertThrows(ServiceException.class, () -> access.checkRoute(100L, 1L, "GET", "/admin-api/crm/trial-business/customer"));
        access.checkRoute(101L, 1L, "GET", "/admin-api/crm/trial-business/customer");
    }

    @Test void unconfirmedApplicationsDoNotStarveConfirmedQueue() {
        var app = submit(); orchestrator.advance(app.id());
        assertTrue(store.pending(20).isEmpty());
        confirm(app.id());
        assertEquals(1, store.pending(20).size());
    }

    @Test void eventsRequireOwnershipActualBusinessEvidenceAndDeduplicateSummaries() {
        var app = submit(); confirm(app.id()); orchestrator.advance(app.id());
        var followUps = org.mockito.Mockito.mock(cn.iocoder.yudao.module.crm.service.followup.CrmFollowUpRecordService.class);
        var events = new TrialEventService(store, jdbc, followUps);
        var event = new cn.iocoder.yudao.module.crm.controller.admin.trial.vo.TrialEventReqVO();
        event.setApplicationId(app.id()); event.setKnowdoMemberId("fixture-member");
        event.setEventId("event-bind-00000001"); event.setType("BOUND");
        events.accept(identity, event); events.accept(identity, event);
        org.mockito.Mockito.verify(followUps, org.mockito.Mockito.times(1)).createFollowUpRecordBatch(org.mockito.ArgumentMatchers.anyList());
        event.setEventId("event-bind-another-id"); events.accept(identity, event);
        org.mockito.Mockito.verify(followUps, org.mockito.Mockito.times(1)).createFollowUpRecordBatch(org.mockito.ArgumentMatchers.anyList());
        event.setEventId("event-business-0001"); event.setType("FIRST_BUSINESS_COMPLETED"); event.setBusinessRecordId("66");
        assertThrows(ServiceException.class, () -> events.accept(identity, event));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM crm_trial_event", Integer.class));
        jdbc.update("INSERT INTO crm_trial_business_operation VALUES(?,?,?,?,?)", app.id(), "business-operation-0001", "hash", 66, Timestamp.from(Instant.now()));
        events.accept(identity, event); events.accept(identity, event);
        org.mockito.Mockito.verify(followUps, org.mockito.Mockito.times(2)).createFollowUpRecordBatch(org.mockito.ArgumentMatchers.anyList());
        event.setBusinessRecordId("67");
        assertThrows(ServiceException.class, () -> events.accept(identity, event));
        event.setBusinessRecordId("66");
        assertThrows(ServiceException.class, () -> events.accept(new TrialIdentity("knowdo", "intruder", "", "", ""), event));
    }

    static class FakeKnowdo implements KnowdoTrialAdapter {
        final Map<String, Result> results = new HashMap<>();
        int writes;
        boolean timeoutAfterWrite;
        boolean lookupUnavailable;
        boolean revokeUnavailable;
        @Override public synchronized Result lookup(TrialStore.Application app, String step) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            if (lookupUnavailable || (revokeUnavailable && step.equals("KNOWDO_REVOKE"))) { throw new IllegalStateException("test outage"); }
            return results.getOrDefault(app.id() + step, new Result(LookupState.ABSENT, Map.of()));
        }
        @Override public synchronized Result ensure(TrialStore.Application app, String step, Map<String, String> resources) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            String key = app.id() + step;
            if (results.containsKey(key)) { fail("orchestrator should reconcile before creating"); }
            writes++;
            var result = new Result(LookupState.COMPLETE, switch (step) {
                case "KNOWDO_MEMBER" -> Map.of("knowdoMemberId", "fixture-member", "knowdoTenantId", "fixture-tenant");
                case "KNOWDO_AUTH" -> Map.of("authorizationId", "fixture-auth");
                case "DELIVERY" -> Map.of("deliveryRef", "fixture-delivery");
                default -> Map.of("revocationId", "fixture-revoke");
            });
            results.put(key, result);
            if (timeoutAfterWrite) { timeoutAfterWrite = false; throw new IllegalStateException("test timeout after remote commit"); }
            return result;
        }
    }
}
