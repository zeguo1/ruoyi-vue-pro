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
import cn.iocoder.yudao.module.crm.controller.admin.trial.TrialSmsVerificationController;
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
    @Resource TrialSmsVerificationService verification;
    @Resource TrialSmsVerificationTest.CapturingSender smsSender;
    final Map<String, String> proofs = new java.util.HashMap<>();
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
        var sms = new TrialProperties.ServiceKey(); sms.setIssuer("fixture-knowdo"); sms.setSecret(SECRET); sms.setCapabilities(Set.of("SMS_VERIFICATION"));
        properties.setKeys(Map.of("tools", tools, "events", events, "sms", sms));
        properties.setSmsVerification(TrialSmsVerificationTest.properties().getSmsVerification());
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.FileSystemResource("../script/trial/V20260915_05__trial_sms_verification.sql")).execute(fixture.jdbc.getDataSource());
        fixture.jdbc.update("DELETE FROM crm_trial_verified_contact"); fixture.jdbc.update("DELETE FROM crm_trial_sms_challenge");
        smsSender.codes.clear(); smsSender.calls.set(0); smsSender.fail = false; proofs.clear();
    }
    @AfterEach void clear() { fixture.clear(); }
    MockHttpServletRequestBuilder signed(String path, String body, String subject, String key, String confirmation) {
        String proof = "submit".equals(path) ? proofs.computeIfAbsent(subject, value -> {
            var identity = TrialSmsVerificationTest.identity(value, "http-verify-action-" + value, "");
            String mobile = "138" + String.format(java.util.Locale.ROOT, "%08d", Math.abs(value.hashCode() % 100_000_000));
            String challenge = verification.send(identity, mobile).challengeId();
            return verification.verify(identity, challenge, smsSender.codes.get(mobile)).verificationToken();
        }) : "";
        return signedAt(BASE + path, body, subject, key, "true", confirmation, proof);
    }
    MockHttpServletRequestBuilder signedAt(String fullPath, String body, String subject, String key, String verified, String confirmation, String proof) {
        var headers = new LinkedHashMap<String, String>(); headers.put("Key", key);
        headers.put("Timestamp", Long.toString(Instant.now().getEpochSecond())); headers.put("Nonce", UUID.randomUUID().toString());
        headers.put("Subject", subject); headers.put("Verified", verified);
        headers.put("Version", "mgs-trial-v2"); headers.put("Verification", proof);
        headers.put("Confirmation", confirmation); headers.put("Idempotency", "http-application-" + subject);
        String canonical = String.join("\n", "mgs-trial-v2", key, headers.get("Timestamp"), headers.get("Nonce"), "POST", fullPath,
                TrialServiceAuth.sha256(body), subject, verified, "", confirmation, headers.get("Idempotency"), proof);
        var request = post(fullPath).secure(true).contentType("application/json").content(body);
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

    @Test void privateSmsCardFlowRequiresActualVerificationAndSeparateConfirmation() throws Exception {
        String mobile = "13800000001"; String subject = "card-owner";
        String sendBody = "{\"mobile\":\"" + mobile + "\"}";
        String sendPath = "/admin-api/crm/trial-verification/send";
        String verifyPath = "/admin-api/crm/trial-verification/verify";
        assertNotEquals(0, call(signedAt(BASE + "submit", SUBMIT, subject, "tools", "true", "", "")).path("code").asInt());
        assertNotEquals(0, call(signedAt(sendPath, sendBody, subject, "tools", "false", "", "")).path("code").asInt());
        assertNotEquals(0, call(signedAt(sendPath, sendBody, subject, "sms", "false", "", "").secure(false)).path("code").asInt());
        assertEquals(0, smsSender.calls.get());
        var sent = call(signedAt(sendPath, sendBody, subject, "sms", "false", "", ""));
        assertEquals(0, sent.path("code").asInt()); assertEquals("SENT", sent.path("data").path("state").asText());
        String challenge = sent.path("data").path("challengeId").asText(); String code = smsSender.codes.get(mobile);
        var request = signedAt(verifyPath, "{\"challengeId\":\"" + challenge + "\",\"code\":\"" + code + "\"}", subject, "sms", "false", "", "");
        var verified = call(request); assertEquals(0, verified.path("code").asInt());
        String proof = verified.path("data").path("verificationToken").asText(); assertEquals(64, proof.length());
        noBusinessWrites();
        assertNotEquals(0, call(request).path("code").asInt()); // Request nonce replay.
        assertNotEquals(0, call(signedAt(BASE + "submit", SUBMIT, "other", "tools", "true", "", proof)).path("code").asInt());
        var result = call(signedAt(BASE + "submit", SUBMIT, subject, "tools", "true", "", proof));
        assertEquals(0, result.path("code").asInt()); String app = result.path("data").path("applicationId").asText();
        assertEquals(mobile, fixture.jdbc.queryForObject("SELECT mobile FROM crm_clue", String.class));
        assertFalse(result.toString().contains(proof)); assertFalse(result.toString().contains(mobile));
        assertFalse(result.toString().contains(code)); assertNull(fixture.store.get(app).confirmedAt());
        assertEquals(1, fixture.count("system_users"));
        assertEquals(1_020_100_006, call(signedAt(BASE + "create-accounts", body(app), subject, "tools", "true", "", "")).path("code").asInt());
        assertEquals(0, call(signedAt(BASE + "create-accounts", body(app), subject, "tools", "true", "confirmed-card-action", "")).path("code").asInt());
        fixture.ready(app); assertEquals("READY", fixture.store.get(app).status());
    }

    @Test void privateVerificationValidationDoesNotEchoOrLogSubmittedCode() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            String sensitive = "fixture-secret-code-invalid";
            String payload = "{\"challengeId\":\"" + UUID.randomUUID() + "\",\"code\":\"" + sensitive + "\"}";
            var response = fixture.mvc.perform(signedAt("/admin-api/crm/trial-verification/verify", payload, "card-owner", "sms", "false", "", "")).andReturn().getResponse();
            assertEquals("no-store", response.getHeader("Cache-Control"));
            assertEquals(1_020_100_016, fixture.json.readTree(response.getContentAsString()).path("code").asInt());
            assertFalse(response.getContentAsString().contains(sensitive));
            assertFalse(logs.list.stream().anyMatch(event -> event.getFormattedMessage().contains(sensitive)
                    || (event.getThrowableProxy() != null && event.getThrowableProxy().getMessage().contains(sensitive))));
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @Test void legacyEmailAttestationCannotCreateANewApplication() throws Exception {
        String stamp = Long.toString(Instant.now().getEpochSecond()), nonce = UUID.randomUUID().toString();
        String canonical = String.join("\n", "mgs-trial-v1", "tools", stamp, nonce, "POST", BASE + "submit",
                TrialServiceAuth.sha256(SUBMIT), "legacy-person", "true", "legacy@example.invalid", "confirmed", "legacy-request-0001");
        var request = post(BASE + "submit").secure(true).contentType("application/json").content(SUBMIT);
        Map.of("Key", "tools", "Timestamp", stamp, "Nonce", nonce, "Subject", "legacy-person", "Verified", "true",
                "Email", "legacy@example.invalid", "Confirmation", "confirmed", "Idempotency", "legacy-request-0001",
                "Signature", TrialServiceAuth.hmac(SECRET, canonical)).forEach((key, value) -> request.header("X-Mgs-Trial-" + key, value));
        assertEquals(1_020_100_016, call(request).path("code").asInt()); noBusinessWrites();
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.cache.annotation.EnableCaching
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @org.springframework.transaction.annotation.EnableTransactionManagement
    @Import({TrialToolController.class, TrialRequestAdvice.class, TrialServiceAuth.class, TrialSmsVerificationController.class, TrialSmsVerificationService.class})
    static class Configuration extends TrialBrowserJourneyTest.Configuration {
        @Bean TrialSmsVerificationTest.CapturingSender trialSmsSender() { return new TrialSmsVerificationTest.CapturingSender(); }
        @Override public void configurePathMatch(PathMatchConfigurer configurer) {
            configurer.addPathPrefix("/admin-api", type -> type.getPackageName().contains(".controller.admin."));
        }
        @Bean @Override SecurityFilterChain security(HttpSecurity http, GlobalExceptionHandler errors, OAuth2TokenCommonApi tokens) throws Exception {
            Set<String> publicPaths = Set.of(BASE + "submit", BASE + "create-accounts", BASE + "status", BASE + "guide",
                    "/admin-api/crm/trial-verification/send", "/admin-api/crm/trial-verification/verify");
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
