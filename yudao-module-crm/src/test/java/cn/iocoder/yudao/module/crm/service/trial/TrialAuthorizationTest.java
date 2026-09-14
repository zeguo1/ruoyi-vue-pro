package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.crm.controller.admin.trial.TrialAuthorizationController;
import cn.iocoder.yudao.module.crm.controller.admin.trial.TrialRequestAdvice;
import cn.iocoder.yudao.module.crm.controller.admin.trial.vo.TrialApplicationReqVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** H2 orchestration + explicit OAuth substitute. Does not claim real Redis/SSO integration. */
class TrialAuthorizationTest {
    TrialStore store;
    JdbcTemplate jdbc;
    TrialOAuthGateway oauth;
    TrialAuthorizationService grants;
    TrialIdentity identity = new TrialIdentity("knowdo", "original-person", "verified@example.invalid", "confirm", "application-000001");
    String id;
    @BeforeEach void setup() {
        var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        new ResourceDatabasePopulator(new FileSystemResource("../script/trial/V20260914_01__trial_onboarding.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        store = new TrialStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)), new ObjectMapper());
        oauth = mock(TrialOAuthGateway.class);
        grants = new TrialAuthorizationService(store, jdbc, oauth);
        when(oauth.create(100, "fixture-client")).thenReturn(888L);
        when(oauth.resolve(100, 888, "fixture-client")).thenReturn(new TrialOAuthGateway.Credential("fixture-secret-only", Instant.now().plusSeconds(600).toString()));
        var policy = new TrialProperties.Policy("fixture", 8, 9, 1, 7, "https://mgs.example.invalid", "https://knowdo.example.invalid", "fixture-client");
        id = store.submit(identity, identity.idempotencyKey(), "测试团队", "测试人", "CRM_FOLLOW_UP", policy, 20).id();
        store.confirm(id, identity);
        store.localDone(id, "MGS", Map.of("userId", "100", "tenantId", "1"));
        store.localDone(id, "DEMO", Map.of("customerId", "500"));
        store.localDone(id, "KNOWDO_MEMBER", Map.of("knowdoMemberId", "member-1"));
    }
    void prepare() { store.localDone(id, "MGS_GRANT", grants.prepare(store.get(id))); }
    @Test void concurrentPreparationAndRetryKeepOneGrantAndNoSecretInState() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            var results = pool.invokeAll(java.util.stream.IntStream.range(0, 8).mapToObj(i -> (Callable<Map<String, String>>) () -> grants.prepare(store.get(id))).toList());
            var reference = results.get(0).get();
            for (var result : results) { assertEquals(reference, result.get()); }
            store.localDone(id, "MGS_GRANT", reference);
            verify(oauth, times(1)).create(100, "fixture-client");
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM crm_trial_authorization", Integer.class));
            assertFalse(store.encode(store.steps(id)).contains("fixture-secret"));
            assertEquals("fixture-secret-only", grants.exchange(id, identity).accessToken());
            assertEquals("TrialCredential[REDACTED]", grants.exchange(id, identity).toString());
        } finally { pool.shutdownNow(); }
    }
    @Test void otherSubjectMissingStepsAndExpiredApplicationCannotReceiveToken() {
        prepare();
        assertThrows(ServiceException.class, () -> grants.exchange(id, new TrialIdentity("knowdo", "intruder", "", "", "")));
        jdbc.update("UPDATE crm_trial_step SET state='PENDING' WHERE application_id=? AND step='KNOWDO_MEMBER'", id);
        assertThrows(ServiceException.class, () -> grants.exchange(id, identity));
        store.localDone(id, "KNOWDO_MEMBER", Map.of("knowdoMemberId", "member-1"));
        jdbc.update("UPDATE crm_trial_application SET expires_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), id);
        assertThrows(ServiceException.class, () -> grants.exchange(id, identity));
        verify(oauth, never()).resolve(anyLong(), anyLong(), anyString());
    }
    @Test void revokedGrantIsNeverSilentlyRecreated() {
        prepare();
        when(oauth.resolve(100, 888, "fixture-client")).thenThrow(TrialException.error(11, "已撤销"));
        assertThrows(RuntimeException.class, () -> grants.exchange(id, identity));
        assertThrows(RuntimeException.class, () -> grants.exchange(id, identity));
        verify(oauth, times(1)).create(100, "fixture-client");
    }
    @Test void dedicatedControllerRequiresSecureTransportAndNeverCachesSecrets() {
        prepare();
        var controller = new TrialAuthorizationController(grants);
        var body = new TrialApplicationReqVO(); body.setApplicationId(id);
        var request = new MockHttpServletRequest();
        request.setAttribute(TrialRequestAdvice.IDENTITY_ATTRIBUTE, identity);
        var response = new MockHttpServletResponse();
        assertThrows(ServiceException.class, () -> controller.exchange(body, request, response));
        request.setSecure(true);
        assertEquals("fixture-secret-only", controller.exchange(body, request, response).getData().accessToken());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertTrue(TrialAuthorizationController.class.isAnnotationPresent(io.swagger.v3.oas.annotations.Hidden.class));
    }
}
