package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.framework.tenant.core.web.TenantContextWebFilter;
import cn.iocoder.yudao.module.system.controller.admin.user.vo.user.UserSaveReqVO;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Joint local journey, not a real KnowDo/WeChat end-to-end claim. Actual MGS provisioning, login HTTP,
 * OAuth persistence/cache DAO, token/tenant filters, permissions and CRM services share an isolated database.
 * KnowDo, dictionary and unrelated module services are explicit fixtures; Redis protocol is jedis-mock. */
@SpringJUnitWebConfig(TrialLocalJourneyConfiguration.class)
@TestPropertySource(properties = {"mgs.trial.storage-enabled=true", "yudao.captcha.enable=false"})
class TrialLocalJourneyIntegrationTest {
    @Resource WebApplicationContext context;
    @Resource JdbcTemplate jdbc;
    @Resource ObjectMapper json;
    @Resource TrialStore store;
    @Resource TrialOrchestrator orchestrator;
    @Resource MgsTrialProvisioner local;
    @Resource KnowdoTrialAdapter knowdo;
    @Resource TrialLoginDeliveryService delivery;
    @Resource TrialAuthorizationService authorizations;
    @Resource TrialEventService events;
    @Resource AdminUserService users;
    @Resource StringRedisTemplate redis;
    @Resource org.springframework.cache.CacheManager caches;
    MockMvc mvc;
    Exception resolvedException;
    long owner;
    final Map<String, KnowdoTrialAdapter.Result> remote = new ConcurrentHashMap<>();
    final AtomicBoolean timeoutAfterAuth = new AtomicBoolean();
    static final String BASE = "/admin-api/crm/trial-business";

    @BeforeEach void setup() {
        TenantContextHolder.clear(); SecurityContextHolder.clearContext(); remote.clear(); timeoutAfterAuth.set(false);
        caches.getCacheNames().forEach(name -> caches.getCache(name).clear());
        // This template is wired exclusively to the per-context ephemeral loopback Redis fixture.
        try (var connection = redis.getConnectionFactory().getConnection()) { connection.serverCommands().flushDb(); }
        for (String table : List.of("crm_trial_authorization", "crm_trial_login_delivery", "crm_trial_business_operation", "crm_trial_event",
                "crm_trial_account", "crm_trial_step", "crm_trial_application", "crm_follow_up_record", "crm_permission", "crm_customer", "crm_clue",
                "system_oauth2_access_token", "system_oauth2_refresh_token", "system_oauth2_client", "system_user_role", "system_role_menu",
                "system_user_post", "system_users", "system_role", "system_menu", "system_tenant", "system_login_log")) {
            jdbc.update("DELETE FROM " + table);
        }
        for (long tenant : List.of(1L, 8L)) jdbc.update("INSERT INTO system_tenant(id,name,contact_name,status,package_id,expire_time,account_count) VALUES(?,?,?,0,0,?,20)",
                tenant, "fixture-" + tenant, "内部管理", LocalDateTime.now().plusYears(1));
        for (var menu : Map.of(21L, "crm:trial-business:query", 22L, "crm:trial-business:follow-up").entrySet()) {
            jdbc.update("INSERT INTO system_menu(id,name,permission,type,parent_id,status,path,component) VALUES(?,?,?,2,0,0,?,?)",
                    menu.getKey(), "fixture-" + menu.getKey(), menu.getValue(), "/trial-experience", "crm/trial/experience/index");
        }
        for (String client : List.of("default", "trial-test")) jdbc.update("INSERT INTO system_oauth2_client(client_id,secret,name,logo,status,access_token_validity_seconds,refresh_token_validity_seconds,redirect_uris,authorized_grant_types,scopes) VALUES(?,?,?,'fixture',0,600,3600,'[]','[\"password\",\"refresh_token\"]','[\"mgs.trial\"]')",
                client, "fixture-client-secret", client);
        owner = TenantUtils.execute(8L, () -> { var req = new UserSaveReqVO(); req.setUsername("operatorfixture");
            req.setNickname("内部运营测试人"); req.setPassword("FixturePass12345"); return users.createUser(req); });
        reset(knowdo);
        when(knowdo.lookup(any(), anyString())).thenAnswer(call -> {
            TrialStore.Application app = call.getArgument(0); String step = call.getArgument(1);
            return remote.getOrDefault(app.id() + step, new KnowdoTrialAdapter.Result(KnowdoTrialAdapter.LookupState.ABSENT, Map.of()));
        });
        when(knowdo.ensure(any(), anyString(), anyMap())).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            TrialStore.Application app = call.getArgument(0); String step = call.getArgument(1);
            var result = new KnowdoTrialAdapter.Result(KnowdoTrialAdapter.LookupState.COMPLETE, switch (step) {
                case "KNOWDO_MEMBER" -> Map.of("knowdoMemberId", "fixture-member-" + app.id(), "knowdoTenantId", "fixture-tenant");
                case "KNOWDO_AUTH" -> Map.of("knowdoAuthorizationId", "fixture-authorization-" + app.id());
                case "DELIVERY" -> Map.of("deliveryRef", "fixture-card-" + app.id());
                case "KNOWDO_REVOKE" -> Map.of("knowdoRevoked", "true");
                default -> throw new AssertionError(step);
            });
            remote.put(app.id() + step, result);
            if (step.equals("KNOWDO_AUTH") && timeoutAfterAuth.compareAndSet(true, false)) throw new IllegalStateException("fixture remote timeout after commit");
            return result;
        });
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new TenantContextWebFilter(),
                context.getBean("springSecurityFilterChain", Filter.class)).build();
    }
    @AfterEach void clear() { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }
    TrialIdentity identity(String subject) { return new TrialIdentity("fixture-knowdo", subject, subject + "@example.invalid", "confirmed", "claim-request-" + subject); }
    String apply(String subject) {
        var identity = identity(subject);
        var policy = new TrialProperties.Policy("fixture", 8, owner, 1, 7, "https://mgs.example.invalid", "https://knowdo.example.invalid", "trial-test");
        var app = store.submit(identity, identity.idempotencyKey(), "虚构体验团队", "体验人", "CRM_FOLLOW_UP", policy, 20);
        store.confirm(app.id(), identity); return app.id();
    }
    void ready(String id) { orchestrator.advance(id); assertEquals("READY", store.get(id).status(), () -> store.steps(id).stream().map(s -> s.name() + ":" + s.state()).toList().toString()); }
    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String token) { return req.header("Authorization", "Bearer " + token).header("tenant-id", "1"); }
    JsonNode call(MockHttpServletRequestBuilder req) throws Exception {
        var result = mvc.perform(req.header("User-Agent", "MGS-isolated-journey-test")).andReturn();
        resolvedException = result.getResolvedException();
        var response = result.getResponse();
        assertEquals(200, response.getStatus()); return json.readTree(response.getContentAsString());
    }
    JsonNode login(String id, String subject) throws Exception {
        var credentials = delivery.claim(id, identity(subject));
        var response = call(post("/admin-api/system/auth/login").header("tenant-id", credentials.tenantId()).contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("username", credentials.username(), "password", credentials.password()))));
        if (response.path("code").asInt() != 0) throw new AssertionError("Actual login failed", resolvedException); assertFalse(response.path("data").path("accessToken").asText().isBlank());
        return response.path("data");
    }
    JsonNode customer(String token) throws Exception { return call(as(get(BASE + "/customer"), token)); }
    JsonNode followUp(String token, String key) throws Exception {
        return call(as(post(BASE + "/follow-up"), token).contentType("application/json").content(json.writeValueAsBytes(Map.of(
                "idempotencyKey", key, "content", "通过 Agent 完成的隔离演示跟进", "type", 1, "nextTime", "2027-01-02T10:30:00"))));
    }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    @Test void twoProvisionedAccountsLoginAndAgentWritesAreVisibleOnlyToTheirOwners() throws Exception {
        String first = apply("first"), second = apply("second"); ready(first); ready(second);
        var login = login(first, "first"); var other = login(second, "second");
        String browser = login.path("accessToken").asText(), otherBrowser = other.path("accessToken").asText();
        String agent = authorizations.exchange(first, identity("first")).accessToken();
        assertNotEquals(browser, agent);
        var one = customer(browser); var two = customer(otherBrowser);
        assertEquals(0, one.path("code").asInt()); assertEquals(0, two.path("code").asInt());
        assertNotEquals(one.path("data").path("id"), two.path("data").path("id"));
        var permissions = call(as(get("/admin-api/system/auth/get-permission-info"), browser));
        assertEquals(0, permissions.path("code").asInt());
        assertEquals(2, permissions.path("data").path("permissions").size());
        assertTrue(permissions.path("data").path("roles").get(0).asText().startsWith("mgs_trial_"));
        var written = followUp(agent, "joint-followup-operation-1"); assertEquals(0, written.path("code").asInt());
        assertEquals(written, followUp(agent, "joint-followup-operation-1"));
        var viewed = call(as(get(BASE + "/follow-ups"), browser));
        assertEquals(1, viewed.path("data").size()); assertEquals(written.path("data"), viewed.path("data").get(0).path("id"));
        assertEquals(0, call(as(get(BASE + "/follow-ups"), otherBrowser)).path("data").size());
        assertEquals(2, count("crm_clue")); assertEquals(2, count("crm_customer")); assertEquals(1, count("crm_follow_up_record"));
        assertEquals(List.of(8L), jdbc.queryForList("SELECT DISTINCT tenant_id FROM crm_clue", Long.class));
        assertEquals(List.of(1L), jdbc.queryForList("SELECT DISTINCT tenant_id FROM crm_customer", Long.class));
        assertThrows(RuntimeException.class, () -> authorizations.exchange(first, identity("second")));
        assertNotEquals(0, call(as(get("/admin-api/system/role/page"), agent)).path("code").asInt());
        assertEquals(403, call(get(BASE + "/customer").header("Authorization", "Bearer " + agent).header("tenant-id", "8")).path("code").asInt());
    }

    @Test void expiryRemovesBothActualLoginAndAgentTokensIncludingCachedRefreshAlias() throws Exception {
        String first = apply("expired"), second = apply("active"); ready(first); ready(second);
        var oldCredentials = delivery.claim(first, identity("expired"));
        var login = login(first, "expired"); String browser = login.path("accessToken").asText();
        String refresh = login.path("refreshToken").asText();
        String agent = authorizations.exchange(first, identity("expired")).accessToken();
        String other = login(second, "active").path("accessToken").asText();
        assertEquals(0, customer(refresh).path("code").asInt()); // Exercise real framework refresh-token bearer cache.
        assertEquals(0, followUp(agent, "before-expiry-operation-1").path("code").asInt());
        jdbc.update("UPDATE crm_trial_application SET expires_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), first);
        assertNotEquals(0, customer(browser).path("code").asInt()); // Interceptor denies before maintenance catches up.
        orchestrator.advance(first); assertEquals("EXPIRED", store.get(first).status());
        for (String token : List.of(browser, refresh, agent)) assertEquals(401, mvc.perform(as(get(BASE + "/customer"), token)).andReturn().getResponse().getStatus());
        assertThrows(RuntimeException.class, () -> authorizations.exchange(first, identity("expired")));
        assertNotEquals(0, call(post("/admin-api/system/auth/login").header("tenant-id", "1").contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("username", oldCredentials.username(), "password", oldCredentials.password())))).path("code").asInt());
        assertEquals(0, customer(other).path("code").asInt());
        assertEquals(1, count("crm_follow_up_record")); assertEquals(2, count("crm_clue"));
    }

    @Test void remoteTimeoutAndNewOrchestratorInstanceReuseCommittedMgsResourcesAndGrant() {
        String id = apply("restarted"); timeoutAfterAuth.set(true); orchestrator.advance(id);
        assertEquals("PROVISIONING", store.get(id).status()); assertEquals("UNKNOWN", store.step(id, "KNOWDO_AUTH").state());
        assertThrows(RuntimeException.class, () -> delivery.claim(id, identity("restarted")));
        Map<String, String> account = store.step(id, "MGS").result();
        int tokens = count("system_oauth2_refresh_token");
        new TrialOrchestrator(store, local, knowdo).advance(id);
        assertEquals("READY", store.get(id).status()); assertEquals(account, store.step(id, "MGS").result());
        assertEquals(1, count("crm_clue")); assertEquals(1, count("crm_customer")); assertEquals(2, count("system_users"));
        assertEquals(tokens, count("system_oauth2_refresh_token"));
        verify(knowdo, times(1)).ensure(any(), eq("KNOWDO_AUTH"), anyMap());
    }
    @Test void concurrentRealProvisioningCreatesEachLocalResourceOnlyOnce() throws Exception {
        String id = apply("concurrent");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var tasks = pool.invokeAll(java.util.stream.IntStream.range(0, 12).mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                orchestrator.advance(id); return null;
            }).toList());
            for (var task : tasks) task.get();
        } finally { pool.shutdownNow(); }
        assertEquals("READY", store.get(id).status());
        assertEquals(1, count("crm_clue")); assertEquals(1, count("crm_customer")); assertEquals(2, count("system_users"));
        assertEquals(1, count("system_role")); assertEquals(1, count("crm_trial_authorization"));
        assertEquals(1, count("system_oauth2_refresh_token"));
    }

    @Test void verifiedBusinessEventsWriteOneCorporateSummaryAndRollbackTogetherOnFailure() throws Exception {
        String id = apply("events"); ready(id);
        var event = new cn.iocoder.yudao.module.crm.controller.admin.trial.vo.TrialEventReqVO();
        event.setApplicationId(id); event.setEventId("fixture-bound-event-01"); event.setType("BOUND");
        event.setKnowdoMemberId(store.step(id, "KNOWDO_MEMBER").result().get("knowdoMemberId"));
        jdbc.execute("ALTER TABLE crm_follow_up_record ADD CONSTRAINT fixture_reject_summary CHECK(tenant_id <> 8)");
        try {
            var error = assertThrows(RuntimeException.class, () -> events.accept(identity("events"), event));
            assertTrue(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(error).getMessage().contains("fixture_reject_summary"));
        } finally { jdbc.execute("ALTER TABLE crm_follow_up_record DROP CONSTRAINT fixture_reject_summary"); }
        assertNull(jdbc.queryForObject("SELECT bound_at FROM crm_trial_application WHERE id=?", Timestamp.class, id));
        assertEquals(0, count("crm_trial_event")); assertEquals(0, count("crm_follow_up_record"));
        events.accept(identity("events"), event); events.accept(identity("events"), event);
        assertEquals(1, count("crm_trial_event")); assertEquals(1, count("crm_follow_up_record"));
        event.setEventId("fixture-first-business-01"); event.setType("FIRST_BUSINESS_COMPLETED"); event.setBusinessRecordId("999999");
        assertThrows(RuntimeException.class, () -> events.accept(identity("events"), event));
        assertNull(jdbc.queryForObject("SELECT first_business_at FROM crm_trial_application WHERE id=?", Timestamp.class, id));
        String agent = authorizations.exchange(id, identity("events")).accessToken();
        var written = followUp(agent, "event-business-operation-01"); assertEquals(0, written.path("code").asInt());
        event.setBusinessRecordId(written.path("data").asText());
        events.accept(identity("events"), event); events.accept(identity("events"), event);
        assertEquals(2, count("crm_trial_event")); assertEquals(3, count("crm_follow_up_record"));
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM crm_follow_up_record WHERE tenant_id=8", Long.class));
        assertEquals(1, call(as(get(BASE + "/follow-ups"), agent)).path("data").size());
        assertNotNull(jdbc.queryForObject("SELECT first_business_at FROM crm_trial_application WHERE id=?", Timestamp.class, id));
    }

}
