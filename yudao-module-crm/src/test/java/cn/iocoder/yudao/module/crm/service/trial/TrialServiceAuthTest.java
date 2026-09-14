package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.crm.controller.admin.trial.vo.TrialSubmitReqVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TrialServiceAuthTest {
    static final String SECRET = "test-only-service-key-not-for-production";
    TrialServiceAuth auth;
    TrialProperties properties;
    @BeforeEach void setup() {
        var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE crm_trial_nonce(key_id VARCHAR(64), nonce VARCHAR(64), expires_at TIMESTAMP, PRIMARY KEY(key_id,nonce))");
        properties = new TrialProperties();
        var key = new TrialProperties.ServiceKey(); key.setIssuer("knowdo"); key.setSecret(SECRET); key.setCapabilities(Set.of("TOOLS"));
        properties.setKeys(Map.of("test-key", key));
        auth = new TrialServiceAuth(properties, jdbc);
    }
    MockHttpServletRequest signed(String body) {
        var req = new MockHttpServletRequest("POST", "/admin-api/crm/trial-tool/submit");
        req.setSecure(true);
        Map<String,String> headers = Map.of("Key", "test-key", "Timestamp", Long.toString(Instant.now().getEpochSecond()),
                "Nonce", UUID.randomUUID().toString(), "Subject", "trusted-user", "Verified", "true", "Email", "verified@example.invalid",
                "Confirmation", "receipt", "Idempotency", "test-request-0001");
        headers.forEach((key, value) -> req.addHeader("X-Mgs-Trial-" + key, value));
        String canonical = String.join("\n", "mgs-trial-v1", headers.get("Key"), headers.get("Timestamp"), headers.get("Nonce"),
                "POST", req.getRequestURI(), TrialServiceAuth.sha256(body), headers.get("Subject"), headers.get("Verified"),
                headers.get("Email"), headers.get("Confirmation"), headers.get("Idempotency"));
        req.addHeader("X-Mgs-Trial-Signature", TrialServiceAuth.hmac(SECRET, canonical));
        return req;
    }
    @Test void signaturePreservesTrustedSubjectAndRejectsReplay() {
        var req = signed("{}");
        assertEquals("trusted-user", auth.verify(req, "{}".getBytes(StandardCharsets.UTF_8), "TOOLS").subjectId());
        assertThrows(ServiceException.class, () -> auth.verify(req, "{}".getBytes(StandardCharsets.UTF_8), "TOOLS"));
    }
    @Test void bodySubjectScopeAndTenantTamperingAreRejected() {
        assertThrows(ServiceException.class, () -> auth.verify(signed("{}"), "{\"tenantId\":1}".getBytes(StandardCharsets.UTF_8), "TOOLS"));
        var subject = signed("{}"); subject.removeHeader("X-Mgs-Trial-Subject"); subject.addHeader("X-Mgs-Trial-Subject", "victim");
        assertThrows(ServiceException.class, () -> auth.verify(subject, "{}".getBytes(StandardCharsets.UTF_8), "TOOLS"));
        var tenant = signed("{}"); tenant.addHeader("tenant-id", "1");
        assertThrows(ServiceException.class, () -> auth.verify(tenant, "{}".getBytes(StandardCharsets.UTF_8), "TOOLS"));
        assertThrows(ServiceException.class, () -> auth.verify(signed("{}"), "{}".getBytes(StandardCharsets.UTF_8), "EVENTS"));
        assertThrows(ServiceException.class, () -> auth.verify(signed("{}"), "{}".getBytes(StandardCharsets.UTF_8), "AUTHORIZATION"));
        assertThrows(ServiceException.class, () -> auth.verify(signed("{}"), "{}".getBytes(StandardCharsets.UTF_8), "DELIVERY"));
    }
    @Test void staleRequestsAndDuplicateHeadersAreRejected() {
        var old = signed("{}"); old.removeHeader("X-Mgs-Trial-Timestamp"); old.addHeader("X-Mgs-Trial-Timestamp", "1");
        assertThrows(ServiceException.class, () -> auth.verify(old, "{}".getBytes(StandardCharsets.UTF_8), "TOOLS"));
        var duplicate = signed("{}"); duplicate.addHeader("X-Mgs-Trial-Subject", "trusted-user");
        assertThrows(ServiceException.class, () -> auth.verify(duplicate, "{}".getBytes(StandardCharsets.UTF_8), "TOOLS"));
    }
    @Test void protectedModelFieldsAreNotSilentlyIgnored() {
        var mapper = new ObjectMapper();
        for (String field : Set.of("subjectId", "tenantId", "roleId", "ownerUserId", "packageId", "expiresAt")) {
            assertThrows(Exception.class, () -> mapper.readValue("{\"team\":\"test\",\"contactName\":\"test\",\"scenario\":\"CRM_FOLLOW_UP\",\"" + field + "\":1}", TrialSubmitReqVO.class));
        }
    }
    @Test void missingProductionSettingsFailClosed() {
        assertThrows(ServiceException.class, properties::newPolicy);
        properties.setEnabled(true);
        assertThrows(ServiceException.class, properties::newPolicy);
    }
    @Test void plaintextAndSelfAssertedForwardedHttpsAreRejected() {
        var request = signed("{}"); request.setSecure(false);
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("Forwarded", "proto=https");
        assertThrows(ServiceException.class, () -> auth.verify(request, "{}".getBytes(StandardCharsets.UTF_8), "TOOLS"));
        request.setSecure(true);
        assertEquals("trusted-user", auth.verify(request, "{}".getBytes(StandardCharsets.UTF_8), "TOOLS").subjectId());
    }
}
