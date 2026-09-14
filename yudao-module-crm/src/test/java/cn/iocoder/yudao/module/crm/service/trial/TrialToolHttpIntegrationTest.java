package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.security.core.filter.TokenAuthenticationFilter;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.security.TenantSecurityWebFilter;
import cn.iocoder.yudao.framework.tenant.core.service.TenantFrameworkService;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.crm.controller.admin.trial.TrialRequestAdvice;
import cn.iocoder.yudao.module.crm.controller.admin.trial.TrialToolController;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Actual request advice, JSON conversion, validation, controller, signature/nonce store and local services.
 * MockMvc secure-request flag tests enforcement, not a real TLS handshake or reverse proxy configuration. */
@SpringJUnitWebConfig(TrialToolHttpIntegrationTest.Configuration.class)
@TestPropertySource(properties = {"mgs.trial.storage-enabled=true", "yudao.captcha.enable=false"})
class TrialToolHttpIntegrationTest {
    static final String BASE = "/admin-api/crm/trial-tool/";
    static final String SECRET = "test-only-service-key-not-for-production";
    static final String SUBMIT = "{\"team\":\"隔离测试团队\",\"contactName\":\"体验人\",\"scenario\":\"CRM_FOLLOW_UP\"}";
    @Resource WebApplicationContext context;
    @Resource TrialProperties properties;
    TrialLocalJourneyIntegrationTest fixture;

    @BeforeEach void setup() {
        fixture = new TrialLocalJourneyIntegrationTest(); context.getAutowireCapableBeanFactory().autowireBean(fixture); fixture.setup();
        properties.setEnabled(true); properties.setEnvironment("isolated-http"); properties.setOperatorTenantId(8L);
        properties.setOwnerUserId(fixture.owner); properties.setDemoTenantId(1L); properties.setDurationDays(7); properties.setMaxApplications(20);
        properties.setKnowdoBaseUrl("https://knowdo.example.invalid"); properties.setMgsLoginUrl("https://mgs.example.invalid");
        properties.setOauthClientId("trial-test"); properties.setOutboundKeyId("fixture"); properties.setOutboundSecret(SECRET);
        var tools = new TrialProperties.ServiceKey(); tools.setIssuer("fixture-knowdo"); tools.setSecret(SECRET); tools.setCapabilities(Set.of("TOOLS"));
        var events = new TrialProperties.ServiceKey(); events.setIssuer("fixture-knowdo"); events.setSecret(SECRET); events.setCapabilities(Set.of("EVENTS"));
        properties.setKeys(Map.of("tools", tools, "events", events));
    }
    @AfterEach void clear() { fixture.clear(); }
    MockHttpServletRequestBuilder signed(String path, String body, String subject, String key, String confirmation) {
        var headers = new LinkedHashMap<String, String>(); headers.put("Key", key);
        headers.put("Timestamp", Long.toString(Instant.now().getEpochSecond())); headers.put("Nonce", UUID.randomUUID().toString());
        headers.put("Subject", subject); headers.put("Verified", "true"); headers.put("Email", subject + "@example.invalid");
        headers.put("Confirmation", confirmation); headers.put("Idempotency", "http-application-" + subject);
        String canonical = String.join("\n", "mgs-trial-v1", key, headers.get("Timestamp"), headers.get("Nonce"), "POST", BASE + path,
                TrialServiceAuth.sha256(body), subject, "true", headers.get("Email"), confirmation, headers.get("Idempotency"));
        var request = post(BASE + path).secure(true).contentType("application/json").content(body);
        headers.forEach((name, value) -> request.header("X-Mgs-Trial-" + name, value));
        return request.header("X-Mgs-Trial-Signature", TrialServiceAuth.hmac(SECRET, canonical));
    }
    MockHttpServletRequestBuilder signed(String path, String body) { return signed(path, body, "http-owner", "tools", "confirmed"); }
    JsonNode call(MockHttpServletRequestBuilder request) throws Exception { return fixture.call(request); }
    String body(String id) throws Exception { return fixture.json.writeValueAsString(Map.of("applicationId", id)); }
    String submit() throws Exception {
        var response = call(signed("submit", SUBMIT)); assertEquals(0, response.path("code").asInt());
        return response.path("data").path("applicationId").asText();
    }
    void noBusinessWrites() {
        assertEquals(0, fixture.count("crm_trial_application")); assertEquals(0, fixture.count("crm_clue"));
        assertEquals(1, fixture.count("system_users")); assertEquals(0, fixture.count("crm_customer")); verifyNoInteractions(fixture.knowdo);
    }

    @Test void acceptedCommandsAndReadOnlyQueriesDoNotClaimAccountReadinessOrReplayWrites() throws Exception {
        String id = submit(); assertEquals(1, fixture.count("crm_clue")); assertEquals(1, fixture.count("system_users"));
        assertEquals(id, submit()); assertEquals(1, fixture.count("crm_clue"));
        var accepted = call(signed("create-accounts", body(id)));
        assertEquals(0, accepted.path("code").asInt()); assertFalse(accepted.path("data").path("accountReady").asBoolean());
        for (String path : Set.of("status", "guide")) {
            var response = call(signed(path, body(id))); assertEquals(0, response.path("code").asInt());
            assertFalse(response.path("data").path("accountReady").asBoolean());
        }
        assertEquals(1, fixture.count("system_users")); verifyNoInteractions(fixture.knowdo);
        fixture.ready(id); // Simulates the existing background job, never a status-query side effect.
        var ready = call(signed("status", body(id))); assertTrue(ready.path("data").path("accountReady").asBoolean());
        var guide = call(signed("guide", body(id))); assertEquals(0, guide.path("code").asInt());
        assertTrue(guide.path("data").path("accountReady").asBoolean());
        assertEquals(fixture.store.step(id, "DEMO").result().get("customerId"), guide.path("data").path("customerId").asText());
        String safeOutput = ready.toString() + guide.toString();
        for (String secretField : Set.of("accessToken", "refreshToken", "password", "ciphertext", "verifiedEmail")) assertFalse(safeOutput.contains(secretField));
        clearInvocations(fixture.knowdo);
        call(signed("status", body(id))); call(signed("guide", body(id))); verifyNoInteractions(fixture.knowdo);
        assertEquals(2, fixture.count("system_users")); assertEquals(1, fixture.count("crm_customer"));
    }

    @Test void validSignaturesDoNotPermitForeignIdentityOrMissingConfirmation() throws Exception {
        String id = submit();
        for (String path : Set.of("create-accounts", "status", "guide")) {
            assertEquals(1_020_100_004, call(signed(path, body(id), "other-owner", "tools", "confirmed")).path("code").asInt());
        }
        assertEquals(1_020_100_006, call(signed("create-accounts", body(id), "http-owner", "tools", "")).path("code").asInt());
        assertEquals(1, fixture.count("system_users")); verifyNoInteractions(fixture.knowdo);
    }

    @Test void unsignedTamperedWrongCapabilityPlaintextAndReplayRequestsAreRejected() throws Exception {
        assertNotEquals(0, call(post(BASE + "submit").secure(true).contentType("application/json").content(SUBMIT)).path("code").asInt());
        assertNotEquals(0, call(signed("submit", SUBMIT).content(SUBMIT.replace("体验人", "篡改人"))).path("code").asInt());
        assertNotEquals(0, call(signed("submit", SUBMIT, "http-owner", "events", "confirmed")).path("code").asInt());
        assertNotEquals(0, call(signed("submit", SUBMIT).secure(false).header("X-Forwarded-Proto", "https")).path("code").asInt());
        for (String tenantHeader : Set.of("tenant-id", "visit-tenant-id")) {
            assertNotEquals(0, call(signed("submit", SUBMIT).header(tenantHeader, "1")).path("code").asInt());
        }
        noBusinessWrites();
        String id = submit(); var status = signed("status", body(id));
        assertEquals(0, call(status).path("code").asInt()); assertEquals(1_020_100_002, call(status).path("code").asInt());
    }

    @Test void invalidAndProtectedModelFieldsFailBeforeCrmOrAccountWrites() throws Exception {
        for (String field : Set.of("subjectId", "tenantId", "roleId", "ownerUserId", "packageId", "expiresAt")) {
            String body = SUBMIT.substring(0, SUBMIT.length() - 1) + ",\"" + field + "\":1}";
            assertNotEquals(0, call(signed("submit", body)).path("code").asInt(), field);
        }
        for (String invalid : Set.of("{}", SUBMIT.replace("体验人", ""), SUBMIT.replace("CRM_FOLLOW_UP", "ADMIN"))) {
            assertNotEquals(0, call(signed("submit", invalid)).path("code").asInt());
        }
        String oversized = "{\"team\":\"" + "x".repeat(17_000) + "\"}";
        assertNotEquals(0, call(signed("submit", oversized)).path("code").asInt());
        noBusinessWrites();
    }

    @Test void disablingNewEnrollmentStillAllowsOwnedStatusAndExistingMaintenance() throws Exception {
        String id = submit(); call(signed("create-accounts", body(id))); properties.setEnabled(false);
        assertEquals(1_020_100_003, call(signed("submit", SUBMIT)).path("code").asInt());
        assertEquals(1_020_100_003, call(signed("create-accounts", body(id))).path("code").asInt());
        assertEquals(0, call(signed("status", body(id))).path("code").asInt());
        fixture.ready(id); assertTrue(call(signed("status", body(id))).path("data").path("accountReady").asBoolean());
        fixture.jdbc.update("UPDATE crm_trial_application SET expires_at=? WHERE id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)), id);
        assertFalse(call(signed("status", body(id))).path("data").path("accountReady").asBoolean());
        fixture.orchestrator.advance(id); assertEquals("EXPIRED", fixture.store.get(id).status());
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.cache.annotation.EnableCaching
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @org.springframework.transaction.annotation.EnableTransactionManagement
    @Import({TrialToolController.class, TrialRequestAdvice.class, TrialServiceAuth.class})
    static class Configuration extends TrialBrowserJourneyTest.Configuration {
        @Override public void configurePathMatch(PathMatchConfigurer configurer) {
            configurer.addPathPrefix("/admin-api", type -> type.getPackageName().contains(".controller.admin."));
        }
        @Bean @Override SecurityFilterChain security(HttpSecurity http, GlobalExceptionHandler errors, OAuth2TokenCommonApi tokens) throws Exception {
            Set<String> publicPaths = Set.of(BASE + "submit", BASE + "create-accounts", BASE + "status", BASE + "guide");
            new cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils(new WebProperties());
            var token = new TokenAuthenticationFilter(new SecurityProperties(), errors, tokens);
            var tenant = new TenantSecurityWebFilter(new WebProperties(), new TenantProperties(), publicPaths, errors, mock(TenantFrameworkService.class));
            return http.csrf(c -> c.disable()).sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(c -> c.requestMatchers(publicPaths.toArray(String[]::new)).permitAll().anyRequest().authenticated())
                    .exceptionHandling(c -> c.authenticationEntryPoint((req, res, ex) -> res.setStatus(401)))
                    .addFilterBefore(token, UsernamePasswordAuthenticationFilter.class)
                    .addFilterAfter(tenant, TokenAuthenticationFilter.class).build();
        }
    }
}
