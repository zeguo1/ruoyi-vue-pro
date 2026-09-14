package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.crm.controller.admin.trial.*;
import cn.iocoder.yudao.module.crm.controller.admin.trial.vo.TrialApplicationReqVO;
import cn.iocoder.yudao.module.crm.job.trial.TrialMaintenanceJob;
import cn.iocoder.yudao.module.system.dal.dataobject.user.AdminUserDO;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Hidden;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real H2, AES-GCM, BCrypt and controller calls. User/tenant services are explicit doubles; no real card/WeChat service. */
class TrialLoginDeliveryTest {
    JdbcTemplate jdbc;
    TrialStore store;
    TrialProperties properties;
    TrialLoginVault vault;
    TrialLoginDeliveryService delivery;
    AdminUserService users;
    TenantService tenants;
    AdminUserDO user;
    String id;
    String password;
    final TrialIdentity identity = new TrialIdentity("knowdo", "verified-person", "person@example.invalid", "confirmed", "safe-card-claim-0001");

    @BeforeEach void setup() {
        var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:login_delivery_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        new ResourceDatabasePopulator(new FileSystemResource("../script/trial/V20260914_01__trial_onboarding.sql"),
                new FileSystemResource("../script/trial/V20260914_04__trial_login_delivery.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds); store = new TrialStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)), new ObjectMapper());
        properties = new TrialProperties(); properties.getLoginDelivery().setActiveKeyId("fixture-key-1");
        properties.getLoginDelivery().setEncryptionKeys(Map.of("fixture-key-1", Base64.getEncoder().encodeToString(new byte[32])));
        vault = new TrialLoginVault(properties); users = mock(AdminUserService.class); tenants = mock(TenantService.class);
        delivery = new TrialLoginDeliveryService(store, jdbc, vault, users, tenants);
        var encoder = new BCryptPasswordEncoder(4);
        password = TrialLoginVault.newPassword(); user = new AdminUserDO().setId(100L).setUsername("trialfixture").setStatus(0).setPassword(encoder.encode(password));
        when(users.getUser(100L)).thenAnswer(call -> { assertEquals(1L, TenantContextHolder.getTenantId()); return user; });
        when(users.isPasswordMatch(anyString(), anyString())).thenAnswer(call -> encoder.matches(call.getArgument(0), call.getArgument(1)));
        var policy = new TrialProperties.Policy("fixture", 8, 90, 1, 7, "https://mgs.example.invalid", "https://knowdo.example.invalid", "fixture-client");
        id = store.submit(identity, "application-key-0001", "虚构团队", "体验人", "CRM_FOLLOW_UP", policy, 20).id();
        store.confirm(id, identity);
        jdbc.update("INSERT INTO crm_trial_account(application_id,user_id,tenant_id,role_id) VALUES(?,100,1,200)", id);
        store.locked(id, app -> { delivery.prepare(app, 100, password); return null; });
        for (String step : TrialStore.PROVISION_STEPS) { store.localDone(id, step, Map.of()); }
        store.status(id, "READY");
    }
    @Test void encryptsOriginalPasswordAndConcurrentDeliveryRetriesNeverResetIt() throws Exception {
        assertEquals(16, password.length());
        assertFalse(jdbc.queryForList("SELECT * FROM crm_trial_login_delivery").toString().contains(password));
        assertFalse(store.encode(store.steps(id)).contains(password));
        var pool = Executors.newFixedThreadPool(4);
        try {
            var results = pool.invokeAll(java.util.stream.IntStream.range(0, 8).mapToObj(i -> (Callable<TrialLoginDeliveryService.Credential>) () -> delivery.claim(id, identity)).toList());
            var first = results.get(0).get();
            assertEquals(password, first.password()); assertEquals("trialfixture", first.username()); assertEquals(1, first.tenantId());
            assertEquals("TrialLoginCredential[REDACTED]", first.toString());
            for (var result : results) { assertEquals(first, result.get()); }
            assertTrue(Instant.parse(first.retryUntil()).isBefore(Instant.now().plusSeconds(301)));
        } finally { pool.shutdownNow(); }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM crm_trial_login_delivery", Integer.class));
        verify(users, never()).updateUserPassword(anyLong(), anyString());
    }
    @Test void wrongIdentityAnotherClaimAndExpiredRetryAreRejected() {
        assertThrows(ServiceException.class, () -> delivery.claim(id, new TrialIdentity("knowdo", "intruder", identity.verifiedEmail(), "confirmed", identity.idempotencyKey())));
        delivery.claim(id, identity);
        assertThrows(ServiceException.class, () -> delivery.claim(id, new TrialIdentity(identity.issuer(), identity.subjectId(), identity.verifiedEmail(), "confirmed", "different-claim-0001")));
        jdbc.update("UPDATE crm_trial_login_delivery SET retry_until=?", Timestamp.from(Instant.now().minusSeconds(1)));
        assertThrows(ServiceException.class, () -> delivery.claim(id, identity));
    }
    @Test void notReadyIncompleteExpiredAndDisabledAccountsCannotReceivePassword() {
        store.status(id, "PROVISIONING"); assertThrows(ServiceException.class, () -> delivery.claim(id, identity));
        store.status(id, "READY"); jdbc.update("UPDATE crm_trial_step SET state='PENDING' WHERE step='KNOWDO_AUTH'");
        assertThrows(ServiceException.class, () -> delivery.claim(id, identity));
        store.localDone(id, "KNOWDO_AUTH", Map.of()); user.setStatus(1);
        assertThrows(ServiceException.class, () -> delivery.claim(id, identity)); user.setStatus(0);
        jdbc.update("UPDATE crm_trial_application SET expires_at=?", Timestamp.from(Instant.now().minusSeconds(1)));
        assertThrows(ServiceException.class, () -> delivery.claim(id, identity));
    }
    @Test void cipherTamperingAndRotationFailClosedWhileRetainedOldKeyStillWorks() {
        var envelope = vault.encrypt("application-A-user-100", password);
        assertThrows(ServiceException.class, () -> vault.decrypt("application-B-user-101", envelope));
        assertThrows(ServiceException.class, () -> vault.decrypt("application-A-user-100", new TrialLoginVault.Envelope(envelope.keyId(), envelope.nonce(), "AAAA")));
        properties.getLoginDelivery().setActiveKeyId("fixture-key-2");
        byte[] secondKey = new byte[32]; java.util.Arrays.fill(secondKey, (byte) 2);
        var keys = new java.util.HashMap<>(properties.getLoginDelivery().getEncryptionKeys()); keys.put("fixture-key-2", Base64.getEncoder().encodeToString(secondKey));
        properties.getLoginDelivery().setEncryptionKeys(keys);
        assertEquals(password, delivery.claim(id, identity).password());
        properties.getLoginDelivery().setEncryptionKeys(Map.of("fixture-key-2", keys.get("fixture-key-2")));
        assertThrows(ServiceException.class, () -> delivery.claim(id, identity));
        assertFalse(properties.toString().contains(keys.get("fixture-key-2")));
    }
    @Test void resetPasswordRevocationAndMaintenancePurgeDoNotRecreateDelivery() {
        user.setPassword(new BCryptPasswordEncoder(4).encode("NewFixture123456"));
        assertThrows(ServiceException.class, () -> delivery.claim(id, identity));
        user.setPassword(new BCryptPasswordEncoder(4).encode(password)); delivery.claim(id, identity);
        jdbc.update("UPDATE crm_trial_login_delivery SET retry_until=?", Timestamp.from(Instant.now().minusSeconds(1)));
        new TrialMaintenanceJob(store, mock(TrialOrchestrator.class), jdbc).execute("");
        assertNull(jdbc.queryForObject("SELECT ciphertext FROM crm_trial_login_delivery", String.class));
        assertThrows(ServiceException.class, () -> delivery.claim(id, identity));
        delivery.revoke(id); assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM crm_trial_login_delivery", Integer.class));
        verify(users, never()).updateUserPassword(anyLong(), anyString());
    }
    @Test void privateControllerRequiresTlsAndTrustedIdentityAndDisablesCaching() throws Exception {
        var controller = new TrialLoginDeliveryController(delivery);
        var body = new TrialApplicationReqVO(); body.setApplicationId(id);
        var request = new MockHttpServletRequest(); var response = new MockHttpServletResponse();
        request.setSecure(true); assertThrows(ServiceException.class, () -> controller.claim(body, request, response));
        request.setAttribute(TrialRequestAdvice.IDENTITY_ATTRIBUTE, identity); request.setSecure(false);
        request.addHeader("X-Forwarded-Proto", "https"); assertThrows(ServiceException.class, () -> controller.claim(body, request, response));
        request.setSecure(true); assertEquals(password, controller.claim(body, request, response).getData().password());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertTrue(TrialLoginDeliveryController.class.isAnnotationPresent(Hidden.class));
        var method = TrialLoginDeliveryController.class.getMethod("claim", TrialApplicationReqVO.class, jakarta.servlet.http.HttpServletRequest.class, jakarta.servlet.http.HttpServletResponse.class);
        assertEquals("DELIVERY", method.getAnnotation(TrialCapability.class).value());
        assertFalse(method.getAnnotation(cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog.class).enable());
    }
    @Test void preparationRequiresTransactionAndMissingEncryptionConfigurationDisablesNewEnrollment() {
        assertThrows(ServiceException.class, () -> delivery.prepare(store.get(id), 100, password));
        properties.setEnabled(true); properties.setEnvironment("fixture"); properties.setDemoTenantId(1L);
        properties.setOperatorTenantId(8L); properties.setOwnerUserId(90L); properties.setDurationDays(7); properties.setMaxApplications(20);
        properties.setOauthClientId("fixture-client"); properties.setMgsLoginUrl("https://mgs.example.invalid"); properties.setKnowdoBaseUrl("https://knowdo.example.invalid");
        properties.setOutboundKeyId("fixture-outbound"); properties.setOutboundSecret("fixture-signature-secret-of-32-chars");
        assertNotNull(properties.newPolicy());
        properties.getLoginDelivery().setEncryptionKeys(Map.of());
        assertFalse(properties.getLoginDelivery().ready()); assertThrows(ServiceException.class, properties::newPolicy);
        assertThrows(ServiceException.class, () -> vault.encrypt("test", password));
    }
}
