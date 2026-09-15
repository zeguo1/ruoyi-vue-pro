package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.security.TenantSecurityWebFilter;
import cn.iocoder.yudao.framework.tenant.core.service.TenantFrameworkService;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.module.crm.controller.admin.trial.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Actual security filters, request advice, MVC, H2, SMS proof and consent. No production services. */
@SpringJUnitWebConfig(TrialConnectorHttpIntegrationTest.Configuration.class)
@TestPropertySource(properties = {"mgs.trial.storage-enabled=true", "yudao.captcha.enable=false"})
class TrialConnectorHttpIntegrationTest {
    static final String BASE = "/admin-api/crm/trial-connector/";
    static final String CARD = "/admin-api/crm/trial-connector-private/";
    static final String TOOLS = "mgs_trial.tools." + "a".repeat(43), SAFE = "mgs_trial.card." + "b".repeat(43);
    @Resource WebApplicationContext context;
    TrialToolHttpIntegrationTest legacy;
    TrialLocalJourneyIntegrationTest fixture;
    TrialProperties properties;
    @BeforeEach void setup() {
        legacy = new TrialToolHttpIntegrationTest(); context.getAutowireCapableBeanFactory().autowireBean(legacy); legacy.setup();
        fixture = legacy.fixture; properties = legacy.properties;
        new ResourceDatabasePopulator(new FileSystemResource("../script/trial/V20260915_07__trial_connector_operation.sql")).execute(fixture.jdbc.getDataSource());
        fixture.jdbc.update("DELETE FROM crm_trial_connector_operation"); fixture.jdbc.update("DELETE FROM crm_trial_connector_consent");
        var connector = new TrialProperties.Connector(); connector.setEnabled(true);
        connector.setKeys(Map.of("tools", key(TOOLS, Set.of("TOOLS")), "card", key(SAFE, Set.of("SMS_VERIFICATION", "CONSENT"))));
        properties.setConnector(connector);
        // Production tenant filter runs outside the selected SecurityFilterChain as a servlet filter.
        var tenant = new TenantSecurityWebFilter(new WebProperties(), new TenantProperties(), Set.of(BASE + "**", CARD + "**"),
                context.getBean(cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler.class), mock(TenantFrameworkService.class));
        fixture.mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new cn.iocoder.yudao.framework.tenant.core.web.TenantContextWebFilter(),
                        context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class), tenant).build();
    }
    @AfterEach void clear() { legacy.clear(); }
    TrialProperties.ConnectorKey key(String token, Set<String> capabilities) {
        var key = new TrialProperties.ConnectorKey(); key.setEnabled(true); key.setIssuer("fixture-knowdo");
        key.setTokenSha256(TrialServiceAuth.sha256(token)); key.setExpiresAt(Instant.now().plusSeconds(3600));
        key.setCapabilities(capabilities); key.setAssistantIds(Set.of("assistant-fixture"));
        key.setAudiences(Set.of("anonymous")); key.setChannels(Set.of("web")); return key;
    }
    Map<String, Object> claims(String actor, String operation) {
        return new LinkedHashMap<>(Map.of("version", 1, "actorId", actor, "audience", "anonymous", "assistantId", "assistant-fixture",
                "conversationId", "conversation-a", "taskId", "task-a", "channel", "web", "operationId", operation));
    }
    String encode(Map<String, Object> claims) throws Exception { return Base64.getUrlEncoder().withoutPadding().encodeToString(fixture.json.writeValueAsBytes(claims)); }
    MockHttpServletRequestBuilder request(String path, String body, String token, String actor, String operation) throws Exception {
        return post(path).secure(true).contentType("application/json").content(body)
                .header("Authorization", "Bearer " + token).header("X-KnowDo-Context", encode(claims(actor, operation)));
    }
    JsonNode call(String path, String body, String token, String actor, String operation) throws Exception {
        var result = fixture.call(request(path, body, token, actor, operation));
        if (result.path("code").asInt() == 500) throw new AssertionError("Unexpected server failure", fixture.resolvedException);
        return result;
    }
    String ref(String id) throws Exception { return fixture.json.writeValueAsString(Map.of("applicationId", id)); }
    String verifiedApplication() throws Exception {
        var sent = call(CARD + "sms/send", "{\"mobile\":\"13800000001\"}", SAFE, "actor-a", "send-a");
        assertEquals(0, sent.path("code").asInt());
        String verify = fixture.json.writeValueAsString(Map.of("challengeId", sent.path("data").path("challengeId").asText(), "code", legacy.smsSender.codes.get("13800000001")));
        var checked = call(CARD + "sms/verify", verify, SAFE, "actor-a", "verify-a");
        assertEquals(0, checked.path("code").asInt()); assertTrue(checked.path("data").path("verified").asBoolean());
        assertFalse(checked.toString().contains("verificationToken")); assertEquals(0, fixture.count("crm_trial_application"));
        var applied = call(BASE + "submit", TrialToolHttpIntegrationTest.SUBMIT, TOOLS, "actor-a", "submit-a");
        assertEquals(0, applied.path("code").asInt()); return applied.path("data").path("applicationId").asText();
    }
    @Test void phoneVerificationConsentAndAgentCommandAreThreeSeparateSteps() throws Exception {
        assertEquals(1_020_100_016, call(BASE + "submit", TrialToolHttpIntegrationTest.SUBMIT, TOOLS, "actor-a", "before-proof").path("code").asInt());
        legacy.noBusinessWrites();
        String id = verifiedApplication(); assertNull(fixture.store.get(id).confirmedAt());
        assertEquals(1_020_100_006, call(BASE + "create-accounts", ref(id), TOOLS, "actor-a", "before-consent").path("code").asInt());
        assertEquals(1_020_100_001, call(CARD + "confirm", ref(id), TOOLS, "actor-a", "forged-consent").path("code").asInt());
        assertEquals(0, call(CARD + "confirm", ref(id), SAFE, "actor-a", "confirm-a").path("code").asInt());
        assertNull(fixture.store.get(id).confirmedAt()); // Card itself must NOT release account provisioning.
        assertEquals("PENDING", fixture.store.step(id, "MGS").state());
        assertEquals(0, call(BASE + "create-accounts", ref(id), TOOLS, "actor-a", "create-a").path("code").asInt());
        assertNotNull(fixture.store.get(id).confirmedAt());
        assertEquals(1, fixture.count("system_users")); verifyNoInteractions(fixture.knowdo);
        assertEquals(0, call(BASE + "create-accounts", ref(id), TOOLS, "actor-a", "create-a").path("code").asInt());
        fixture.ready(id);
        var ready = call(BASE + "status", ref(id), TOOLS, "actor-a", "status-a");
        assertTrue(ready.path("data").path("accountReady").asBoolean());
        assertFalse(ready.toString().contains("password"));
    }
    @Test void scopeRevocationExpiryAndDefaultDisabledAreEnforced() throws Exception {
        assertEquals(1_020_100_001, call(CARD + "sms/send", "{\"mobile\":\"13800000001\"}", TOOLS, "actor-a", "x").path("code").asInt());
        assertEquals(1_020_100_001, call(BASE + "submit", TrialToolHttpIntegrationTest.SUBMIT, SAFE, "actor-a", "x").path("code").asInt());
        properties.getConnector().getKeys().get("tools").setEnabled(false);
        assertEquals(1_020_100_001, call(BASE + "submit", TrialToolHttpIntegrationTest.SUBMIT, TOOLS, "actor-a", "x").path("code").asInt());
        properties.getConnector().getKeys().get("tools").setEnabled(true);
        properties.getConnector().getKeys().get("tools").setExpiresAt(Instant.now().minusSeconds(1));
        assertEquals(1_020_100_001, call(BASE + "submit", TrialToolHttpIntegrationTest.SUBMIT, TOOLS, "actor-a", "x").path("code").asInt());
        legacy.noBusinessWrites();
    }
    @Test void rotationKeepsIdentityAndOldKeyCanBeRevokedIndependently() throws Exception {
        String id = verifiedApplication(), next = "mgs_trial.next." + "c".repeat(43);
        var keys = new HashMap<>(properties.getConnector().getKeys()); keys.put("next", key(next, Set.of("TOOLS")));
        properties.getConnector().setKeys(keys);
        assertEquals(id, call(BASE + "submit", TrialToolHttpIntegrationTest.SUBMIT, next, "actor-a", "submit-a").path("data").path("applicationId").asText());
        keys.get("tools").setEnabled(false);
        assertEquals(1_020_100_001, call(BASE + "status", ref(id), TOOLS, "actor-a", "status-old").path("code").asInt());
        assertEquals(0, call(BASE + "status", ref(id), next, "actor-a", "status-new").path("code").asInt());
        assertEquals(1, fixture.count("crm_trial_application"));
    }
    @Test void exactSmsRetryDoesNotResendAndServiceTokenCannotAccessOrdinaryApis() throws Exception {
        String body = "{\"mobile\":\"13800000001\"}";
        var first = call(CARD + "sms/send", body, SAFE, "actor-a", "send-a");
        var retry = call(CARD + "sms/send", body, SAFE, "actor-a", "send-a");
        assertEquals(0, first.path("code").asInt()); assertEquals(first, retry);
        assertEquals(1, legacy.smsSender.calls.get());
        assertEquals(1_020_100_005, call(CARD + "sms/send", body.replace("0001", "0002"), SAFE, "actor-a", "send-a").path("code").asInt());
        assertEquals(1, legacy.smsSender.calls.get());
        var response = fixture.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/admin-api/system/user/profile/get")
                .header("Authorization", "Bearer " + TOOLS).header("tenant-id", "1")).andReturn().getResponse();
        assertEquals(401, response.getStatus()); legacy.noBusinessWrites();
    }
    @Test void expiredProofAndForeignActorCannotSubmitAndNoIdentityBodyFieldsAreAccepted() throws Exception {
        verifiedApplication();
        assertEquals(1_020_100_016, call(BASE + "submit", TrialToolHttpIntegrationTest.SUBMIT, TOOLS, "actor-b", "foreign-proof").path("code").asInt());
        fixture.jdbc.update("UPDATE crm_trial_sms_challenge SET proof_expires_at=?", Timestamp.from(Instant.now().minusSeconds(1)));
        assertEquals(1_020_100_016, call(BASE + "submit", TrialToolHttpIntegrationTest.SUBMIT, TOOLS, "actor-a", "expired-proof").path("code").asInt());
        var invalid = call(BASE + "submit", TrialToolHttpIntegrationTest.SUBMIT.replace("}", ",\"confirmation\":\"yes\"}"), TOOLS, "actor-a", "body-confirmation");
        assertNotEquals(0, invalid.path("code").asInt()); assertEquals(1, fixture.count("crm_trial_application"));
        assertEquals(1, fixture.count("system_users"));
    }
    @Test void malformedContextMixedScopesAndOversizedRequestsFailClosed() throws Exception {
        for (String json : List.of("{}", "null", "{\"version\":1,\"version\":1}", fixture.json.writeValueAsString(claims("actor-a", "x")) + " {}")) {
            var req = post(BASE + "submit").secure(true).contentType("application/json").content("{}")
                    .header("Authorization", "Bearer " + TOOLS).header("X-KnowDo-Context", Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals(1_020_100_001, fixture.call(req).path("code").asInt());
        }
        assertEquals(1_020_100_001, call(BASE + "submit", " ".repeat(16385), TOOLS, "actor-a", "huge").path("code").asInt());
        properties.getConnector().getKeys().get("tools").setCapabilities(Set.of("TOOLS", "CONSENT"));
        assertEquals(1_020_100_001, call(BASE + "submit", "{}", TOOLS, "actor-a", "mixed").path("code").asInt());
        properties.setConnector(new TrialProperties.Connector());
        assertEquals(1_020_100_001, call(BASE + "submit", "{}", TOOLS, "actor-a", "disabled").path("code").asInt());
        assertEquals(0, fixture.count("crm_trial_connector_operation")); legacy.noBusinessWrites();
    }
    @Test void operationReuseWithChangedPayloadOrActorIsRejectedBeforeBusinessWrites() throws Exception {
        String id = verifiedApplication();
        assertEquals(id, call(BASE + "submit", TrialToolHttpIntegrationTest.SUBMIT, TOOLS, "actor-a", "submit-a").path("data").path("applicationId").asText());
        assertEquals(1_020_100_005, call(BASE + "submit", TrialToolHttpIntegrationTest.SUBMIT.replace("体验人", "另一个人"), TOOLS, "actor-a", "submit-a").path("code").asInt());
        assertEquals(1_020_100_005, call(BASE + "submit", TrialToolHttpIntegrationTest.SUBMIT, TOOLS, "actor-b", "submit-a").path("code").asInt());
        for (String action : Set.of("status", "guide", "create-accounts"))
            assertEquals(1_020_100_004, call(BASE + action, ref(id), TOOLS, "actor-b", "foreign-" + action).path("code").asInt());
        assertEquals(1_020_100_004, call(CARD + "confirm", ref(id), SAFE, "actor-b", "foreign-confirm").path("code").asInt());
        assertEquals(1, fixture.count("crm_trial_application")); assertNull(fixture.store.get(id).confirmedAt());
    }
    @Test void expiredConsentCannotBeExtendedByReplayAndStillNeedsFreshUserAction() throws Exception {
        String id = verifiedApplication(); call(CARD + "confirm", ref(id), SAFE, "actor-a", "confirm-a");
        fixture.jdbc.update("UPDATE crm_trial_connector_consent SET expires_at=?", Timestamp.from(Instant.now().minusSeconds(1)));
        call(CARD + "confirm", ref(id), SAFE, "actor-a", "confirm-a");
        assertEquals(1_020_100_006, call(BASE + "create-accounts", ref(id), TOOLS, "actor-a", "create-a").path("code").asInt());
        assertNull(fixture.store.get(id).confirmedAt());
        call(CARD + "confirm", ref(id), SAFE, "actor-a", "confirm-new");
        fixture.jdbc.update("UPDATE crm_trial_connector_operation SET created_at=? WHERE operation_hash=?",
                Timestamp.from(Instant.now().minusSeconds(301)), TrialServiceAuth.sha256("confirm-a"));
        assertEquals(1_020_100_006, call(CARD + "confirm", ref(id), SAFE, "actor-a", "confirm-a").path("code").asInt());
        assertEquals(0, call(BASE + "create-accounts", ref(id), TOOLS, "actor-a", "create-new").path("code").asInt());
    }
    @Test void forgedContextHttpTenantHeadersAndAdministratorTokensCannotAuthorize() throws Exception {
        for (var req : List.of(request(BASE + "submit", "{}", TOOLS, "actor-a", "x").secure(false).header("X-Forwarded-Proto", "https"),
                request(BASE + "submit", "{}", TOOLS, "actor-a", "x").header("tenant-id", "1"),
                request(BASE + "submit", "{}", TOOLS, "actor-a", "x").header("visit-tenant-id", "1"),
                request(BASE + "submit", "{}", TOOLS, "actor-a", "x").header("X-Mgs-Trial-Confirmation", "fake"),
                request(BASE + "submit", "{}", "ordinary-admin-access-token", "actor-a", "x"),
                request(BASE + "submit", "{}", TOOLS, "actor-a", "x").header("X-KnowDo-Context", "duplicate"))) {
            assertEquals(1_020_100_001, fixture.call(req).path("code").asInt());
        }
        for (var change : List.of(Map.<String,Object>of("assistantId", "foreign"), Map.<String,Object>of("audience", "employee"),
                Map.<String,Object>of("channel", "foreign"), Map.<String,Object>of("verified", true), Map.<String,Object>of("version", 4294967297L))) {
            var c = claims("actor-a", "x"); c.putAll(change);
            var req = post(BASE + "submit").secure(true).contentType("application/json").content("{}")
                    .header("Authorization", "Bearer " + TOOLS).header("X-KnowDo-Context", encode(c));
            assertEquals(1_020_100_001, fixture.call(req).path("code").asInt());
        }
        assertEquals(0, fixture.count("crm_trial_connector_operation")); legacy.noBusinessWrites();
    }
    @Test void invalidPrivateBodyDoesNotEchoSmsCodeOrCreateBusinessRecords() throws Exception {
        String sensitive = "not-a-real-secret-code";
        var response = fixture.mvc.perform(request(CARD + "sms/verify", "{\"challengeId\":\"" + UUID.randomUUID() + "\",\"code\":\"" + sensitive + "\"}", SAFE, "actor-a", "verify-bad"))
                .andReturn().getResponse();
        assertTrue(response.getHeader("Cache-Control").contains("no-store"));
        assertEquals(1_020_100_016, fixture.json.readTree(response.getContentAsString()).path("code").asInt());
        assertFalse(response.getContentAsString().contains(sensitive)); legacy.noBusinessWrites();
    }

    @org.springframework.context.annotation.Configuration
    @Import({TrialConnectorController.class, TrialConnectorCardController.class, TrialConnectorRequestAdvice.class, TrialConnectorAuth.class, TrialConnectorConsent.class, cn.iocoder.yudao.module.crm.framework.trial.TrialConnectorSecurityConfiguration.class})
    static class Configuration extends TrialToolHttpIntegrationTest.Configuration {
        @Bean WebProperties connectorWebProperties() { return new WebProperties(); }
    }
}
