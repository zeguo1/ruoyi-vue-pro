package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCheckRespDTO;
import cn.iocoder.yudao.framework.common.biz.system.permission.PermissionCommonApi;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.web.TenantContextWebFilter;
import cn.iocoder.yudao.module.crm.enums.common.CrmBizTypeEnum;
import cn.iocoder.yudao.module.crm.enums.permission.CrmPermissionLevelEnum;
import cn.iocoder.yudao.module.crm.dal.mysql.customer.CrmCustomerMapper;
import cn.iocoder.yudao.module.system.api.dict.DictDataApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real MVC, token/tenant filters, method security, CRM permission aspect, services, MyBatis and transactions.
 * OAuth validation, system menu permissions, dictionary and user directory are explicit fixtures, not live services.
 * Tests never connect to an external database, Redis, KnowDo or WeChat. */
@SpringJUnitWebConfig(TrialBusinessIntegrationConfiguration.class)
@TestPropertySource(properties = "mgs.trial.storage-enabled=true")
class TrialBusinessIntegrationTest {
    @Resource WebApplicationContext context;
    @Resource JdbcTemplate jdbc;
    @Resource TrialStore store;
    @Resource ObjectMapper json;
    @Resource OAuth2TokenCommonApi tokens;
    @Resource PermissionCommonApi permissions;
    @Resource AdminUserApi users;
    @Resource DictDataApi dict;
    @Resource CrmCustomerMapper customers;
    @Resource TrialBusinessIntegrationConfiguration.DeniedRouteProbe probe;
    MockMvc mvc;
    Exception lastResolvedException;
    String first;
    String second;
    static final String BASE = "/admin-api/crm/trial-business";
    static final int CUSTOMER = CrmBizTypeEnum.CRM_CUSTOMER.getType();

    @BeforeEach void setup() {
        TenantContextHolder.clear(); SecurityContextHolder.clearContext();
        for (String table : List.of("crm_trial_business_operation", "crm_trial_account", "crm_trial_step", "crm_trial_application",
                "crm_follow_up_record", "crm_permission", "crm_customer")) { jdbc.update("DELETE FROM " + table); }
        reset(tokens, permissions, users, dict); probe.calls.set(0);
        when(users.getUserListBySubordinate(anyLong())).thenReturn(List.of());
        when(permissions.hasAnyPermissions(anyLong(), any(String[].class))).thenAnswer(call -> {
            Long user = call.getArgument(0);
            // Exactly the two ordinary business permissions; no CRM admin or tenant-visit permission.
            return (user == 100L || user == 101L) && List.of("crm:trial-business:query", "crm:trial-business:follow-up")
                    .contains(call.getArgument(1, String.class));
        });
        for (long user : List.of(100L, 101L, 999L)) {
            when(tokens.checkAccessToken("fixture-token-" + user)).thenReturn(new OAuth2AccessTokenCheckRespDTO()
                    .setUserId(user).setUserType(2).setTenantId(1L).setScopes(List.of("mgs.trial"))
                    .setUserInfo(Map.of()).setExpiresTime(LocalDateTime.now().plusMinutes(10)));
        }
        first = account(100, 1000); second = account(101, 1001);
        // Corporate and other ordinary demo records must not appear in the trial result.
        jdbc.update("INSERT INTO crm_customer(id,name,owner_user_id,tenant_id) VALUES(?,?,?,?)", 8000, "运营真实数据占位", 900, 8);
        jdbc.update("INSERT INTO crm_customer(id,name,owner_user_id,tenant_id) VALUES(?,?,?,?)", 9000, "既有演示数据占位", 999, 1);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new TenantContextWebFilter(),
                context.getBean("springSecurityFilterChain", Filter.class)).build();
    }
    String account(long user, long customer) {
        var identity = new TrialIdentity("fixture-knowdo", "subject-" + user, user + "@example.invalid", "confirmed", "application-key-" + user);
        var policy = new TrialProperties.Policy("isolated-test", 8, 900, 1, 7, "https://mgs.example.invalid", "https://knowdo.example.invalid", "trial-test");
        var app = store.submit(identity, identity.idempotencyKey(), "虚构团队", "体验人", "CRM_FOLLOW_UP", policy, 30);
        store.confirm(app.id(), identity);
        store.localDone(app.id(), "DEMO", Map.of("customerId", Long.toString(customer)));
        store.status(app.id(), "READY"); // Setup fixture, not an assertion that real two-sided provisioning completed.
        jdbc.update("INSERT INTO crm_trial_account(application_id,tenant_id,user_id,role_id) VALUES(?,1,?,?)", app.id(), user, user + 20);
        jdbc.update("INSERT INTO crm_customer(id,name,owner_user_id,tenant_id,follow_up_status) VALUES(?,?,?,1,FALSE)", customer, "虚构客户-" + user, user);
        jdbc.update("INSERT INTO crm_permission(biz_type,biz_id,user_id,level,tenant_id) VALUES(?,?,?,?,1)", CUSTOMER, customer, user, CrmPermissionLevelEnum.OWNER.getLevel());
        return app.id();
    }
    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, long user) {
        return request.header("Authorization", "Bearer fixture-token-" + user).header("tenant-id", "1");
    }
    Map<String, Object> followUp(String key) {
        return Map.of("idempotencyKey", key, "content", "已向虚构客户介绍体验流程", "type", 1, "nextTime", "2027-01-02T10:30:00");
    }
    JsonNode result(MockHttpServletRequestBuilder request) throws Exception {
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        lastResolvedException = result.getResolvedException();
        return json.readTree(result.getResponse().getContentAsString());
    }
    JsonNode write(long user, Map<String, Object> body) throws Exception {
        return result(as(post(BASE + "/follow-up"), user).contentType("application/json").content(json.writeValueAsBytes(body)));
    }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    @Test void actualCustomerAndFollowUpServicesIsolateTwoUsersAndPersistAnIdempotentWrite() throws Exception {
        assertEquals(1000, result(as(get(BASE + "/customer"), 100)).path("data").path("id").asLong());
        assertEquals(1001, result(as(get(BASE + "/customer"), 101)).path("data").path("id").asLong());
        var response = write(100, followUp("business-operation-0001"));
        assertEquals(0, response.path("code").asInt(), () -> String.valueOf(lastResolvedException)); assertTrue(response.path("data").asLong() > 0);
        assertEquals(response, write(100, followUp("business-operation-0001")));
        assertEquals(1, count("crm_follow_up_record")); assertEquals(1, count("crm_trial_business_operation"));
        assertEquals(1, result(as(get(BASE + "/follow-ups"), 100)).path("data").size());
        assertEquals(0, result(as(get(BASE + "/follow-ups"), 101)).path("data").size());
        assertEquals(1000L, jdbc.queryForObject("SELECT biz_id FROM crm_follow_up_record", Long.class));
        assertEquals("100", jdbc.queryForObject("SELECT creator FROM crm_follow_up_record", String.class));
        assertEquals(1L, jdbc.queryForObject("SELECT tenant_id FROM crm_follow_up_record", Long.class));
        assertEquals(true, jdbc.queryForObject("SELECT follow_up_status FROM crm_customer WHERE id=1000", Boolean.class));
        assertEquals(false, jdbc.queryForObject("SELECT follow_up_status FROM crm_customer WHERE id=1001", Boolean.class));
        var changed = new java.util.HashMap<>(followUp("business-operation-0001")); changed.put("content", "不同内容");
        assertNotEquals(0, write(100, changed).path("code").asInt());
        assertEquals(1, count("crm_follow_up_record"));
        // The same client operation key belongs to the authenticated applicant, not a shared tenant namespace.
        assertEquals(0, write(101, followUp("business-operation-0001")).path("code").asInt());
        assertEquals(2, count("crm_follow_up_record"));
    }

    @Test void mvcValidationAndForbiddenFieldsRejectBeforeAnyBusinessInsert() throws Exception {
        for (String field : List.of("content", "type", "nextTime", "idempotencyKey")) {
            var invalid = new java.util.HashMap<>(followUp("invalid-operation-0001")); invalid.remove(field);
            assertEquals(400, write(100, invalid).path("code").asInt(), field);
        }
        for (String field : List.of("bizId", "customerId", "tenantId", "ownerUserId", "roleId", "fileUrls", "contactIds")) {
            var invalid = new java.util.HashMap<>(followUp("invalid-operation-0001")); invalid.put(field, 1001);
            assertEquals(400, write(100, invalid).path("code").asInt(), field);
        }
        assertEquals(0, count("crm_follow_up_record")); assertEquals(0, count("crm_trial_business_operation"));
        verifyNoInteractions(dict);
    }

    @Test void crmPermissionAspectAndOwnershipGuardRejectForeignOrTransferredCustomers() throws Exception {
        store.localDone(first, "DEMO", Map.of("customerId", "1001")); // Simulate incorrect mapping: never leak another user's record.
        assertNotEquals(0, result(as(get(BASE + "/customer"), 100)).path("code").asInt());
        assertNotEquals(0, write(100, followUp("foreign-operation-0001")).path("code").asInt());
        store.localDone(first, "DEMO", Map.of("customerId", "1000"));
        jdbc.update("UPDATE crm_customer SET owner_user_id=101 WHERE id=1000");
        assertNotEquals(0, result(as(get(BASE + "/customer"), 100)).path("code").asInt());
        jdbc.update("UPDATE crm_customer SET owner_user_id=100 WHERE id=1000");
        jdbc.update("UPDATE crm_permission SET level=? WHERE biz_id=1000", CrmPermissionLevelEnum.READ.getLevel());
        assertNotEquals(0, write(100, followUp("readonly-operation-0001")).path("code").asInt());
        assertEquals(0, count("crm_follow_up_record"));
        // Real MyBatis tenant SQL prevents even a mapper read under the operator tenant from finding demo data.
        try { TenantContextHolder.setTenantId(8L); assertNull(customers.selectById(1000L)); }
        finally { TenantContextHolder.clear(); }
    }

    @Test void realFiltersAndInterceptorDenyAnonymousWrongTenantExportsFilesAndExpiredAccounts() throws Exception {
        mvc.perform(get(BASE + "/customer").header("tenant-id", "1")).andExpect(status().isUnauthorized());
        assertEquals(403, result(get(BASE + "/customer").header("Authorization", "Bearer fixture-token-100").header("tenant-id", "8")).path("code").asInt());
        assertEquals(403, result(as(get(BASE + "/customer"), 100).header("visit-tenant-id", "8")).path("code").asInt());
        for (String path : List.of("/admin-api/crm/customer/page", "/admin-api/crm/customer/export-excel",
                "/admin-api/infra/file/1/get/example.txt", "/admin-api/crm/clue/page", "/admin-api/system/role/page")) {
            assertNotEquals(0, result(as(get(path), 100)).path("code").asInt());
        }
        assertEquals(0, probe.calls.get());
        result(as(get("/admin-api/crm/customer/page"), 999)); assertEquals(1, probe.calls.get());
        jdbc.update("UPDATE crm_trial_application SET expires_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), first);
        assertNotEquals(0, result(as(get(BASE + "/customer"), 100)).path("code").asInt());
        assertNotEquals(0, write(100, followUp("expired-operation-0001")).path("code").asInt());
        assertEquals(0, result(as(get(BASE + "/customer"), 101)).path("code").asInt());
        assertEquals(0, count("crm_follow_up_record"));
    }

    @Test void springMethodSecurityRejectsMissingBusinessPermissionBeforeWrite() throws Exception {
        when(permissions.hasAnyPermissions(eq(100L), eq("crm:trial-business:follow-up"))).thenReturn(false);
        assertEquals(403, write(100, followUp("no-permission-00001")).path("code").asInt());
        assertEquals(0, count("crm_follow_up_record")); verifyNoInteractions(dict);
    }

    @Test void concurrentHttpRetriesCommitOnlyOneRealFollowUp() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            byte[] body = json.writeValueAsBytes(followUp("concurrent-operation-0001"));
            var futures = pool.invokeAll(java.util.stream.IntStream.range(0, 12).mapToObj(i -> (Callable<Long>) () -> {
                var response = mvc.perform(as(post(BASE + "/follow-up"), 100).contentType("application/json").content(body))
                        .andExpect(status().isOk()).andReturn();
                var node = json.readTree(response.getResponse().getContentAsString());
                assertEquals(0, node.path("code").asInt(), () -> String.valueOf(response.getResolvedException()));
                assertTrue(node.path("data").isIntegralNumber());
                return node.path("data").asLong();
            }).toList());
            long id = futures.get(0).get();
            for (var future : futures) { assertEquals(id, future.get()); }
        } finally { pool.shutdownNow(); }
        assertEquals(1, count("crm_follow_up_record")); assertEquals(1, count("crm_trial_business_operation"));
    }

    @Test void invalidDictionaryValueAndRevokedTokenCannotWrite() throws Exception {
        doThrow(TrialException.error(12, "测试字典中没有此跟进类型"))
                .when(dict).validateDictDataList(eq("crm_follow_up_type"), eq(List.of("999")));
        var body = new java.util.HashMap<>(followUp("invalid-type-operation-01")); body.put("type", 999);
        assertEquals(1_020_100_012, write(100, body).path("code").asInt());
        assertEquals(0, count("crm_follow_up_record"));
        when(tokens.checkAccessToken("fixture-token-100")).thenReturn(null);
        mvc.perform(as(post(BASE + "/follow-up"), 100).contentType("application/json")
                .content(json.writeValueAsBytes(followUp("revoked-operation-0001")))).andExpect(status().isUnauthorized());
        assertEquals(0, count("crm_trial_business_operation"));
    }

    @Test void failureAfterRealCrmInsertRollsBackRecordCustomerStateAndIdempotencyMarker() throws Exception {
        jdbc.execute("ALTER TABLE crm_trial_business_operation ADD CONSTRAINT fixture_reject_marker CHECK (idempotency_key <> 'force-marker-failure-0001')");
        try {
            assertNotEquals(0, write(100, followUp("force-marker-failure-0001")).path("code").asInt());
            assertNotNull(lastResolvedException);
            assertTrue(lastResolvedException.getMessage().contains("FIXTURE_REJECT_MARKER"), () -> String.valueOf(lastResolvedException));
            assertEquals(0, count("crm_follow_up_record")); assertEquals(0, count("crm_trial_business_operation"));
            assertEquals(false, jdbc.queryForObject("SELECT follow_up_status FROM crm_customer WHERE id=1000", Boolean.class));
            assertNull(jdbc.queryForObject("SELECT contact_last_content FROM crm_customer WHERE id=1000", String.class));
        } finally { jdbc.execute("ALTER TABLE crm_trial_business_operation DROP CONSTRAINT fixture_reject_marker"); }
        assertEquals(0, write(100, followUp("force-marker-failure-0001")).path("code").asInt(), () -> String.valueOf(lastResolvedException));
        assertEquals(1, count("crm_follow_up_record")); assertEquals(1, count("crm_trial_business_operation"));
    }
}
