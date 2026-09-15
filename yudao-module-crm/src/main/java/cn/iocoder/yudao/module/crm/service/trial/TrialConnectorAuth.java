package cn.iocoder.yudao.module.crm.service.trial;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Dedicated service bearer + context asserted by the authenticated KnowDo backend, never by the model. */
@Service @RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
public class TrialConnectorAuth {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> FIELDS = Set.of("version", "actorId", "audience", "assistantId", "conversationId", "taskId", "channel", "channelId", "operationId");
    private static final Set<String> CAPABILITIES = Set.of("TOOLS", "SMS_VERIFICATION", "CONSENT");
    private final TrialProperties properties;
    private final JdbcTemplate jdbc;

    public TrialIdentity verify(HttpServletRequest request, byte[] body, String capability) {
        var config = properties.getConnector();
        if (config == null || !config.isEnabled() || !request.isSecure() || !"POST".equals(request.getMethod())
                || body.length > 16_384 || request.getQueryString() != null
                || request.getHeader("tenant-id") != null || request.getHeader("visit-tenant-id") != null) {
            throw TrialException.unauthorized();
        }
        // Do not mix legacy identity/verification/confirmation assertions into this protocol.
        for (String name : Collections.list(request.getHeaderNames())) {
            if (name.toLowerCase(Locale.ROOT).startsWith("x-mgs-trial-")) throw TrialException.unauthorized();
        }
        String auth = header(request, "Authorization", 256);
        if (!auth.matches("Bearer mgs_trial\\.[a-zA-Z0-9_-]{1,48}\\.[a-zA-Z0-9_-]{43,128}")) throw TrialException.unauthorized();
        String token = auth.substring(7), keyId = token.split("\\.")[1];
        var key = config.getKeys().get(keyId);
        if (key == null || !key.isEnabled() || key.getExpiresAt() == null || !key.getExpiresAt().isAfter(Instant.now())
                || key.getIssuer() == null || !key.getIssuer().matches("[a-zA-Z0-9_-]{1,64}")
                || key.getTokenSha256() == null || !key.getTokenSha256().matches("[a-f0-9]{64}")
                || !MessageDigest.isEqual(key.getTokenSha256().getBytes(StandardCharsets.US_ASCII),
                    TrialServiceAuth.sha256(token).getBytes(StandardCharsets.US_ASCII))
                || !CAPABILITIES.containsAll(key.getCapabilities()) || !key.getCapabilities().contains(capability)
                || (key.getCapabilities().contains("TOOLS") && key.getCapabilities().size() != 1)) {
            throw TrialException.unauthorized();
        }
        JsonNode context;
        try {
            String encoded = header(request, "X-KnowDo-Context", 4096);
            if (!encoded.matches("[a-zA-Z0-9_-]+")) throw TrialException.unauthorized();
            context = JSON.readTree(Base64.getUrlDecoder().decode(encoded));
            if (context == null || !context.isObject()) throw TrialException.unauthorized();
        } catch (Exception ignored) { throw TrialException.unauthorized(); }
        for (String name : iterable(context.fieldNames())) if (!FIELDS.contains(name)) throw TrialException.unauthorized();
        if (!context.path("version").isIntegralNumber() || !context.path("version").canConvertToInt() || context.path("version").intValue() != 1) throw TrialException.unauthorized();
        var values = new TreeMap<String, String>();
        for (String name : FIELDS) {
            if (name.equals("version") || (name.equals("channelId") && !context.has(name))) continue;
            JsonNode field = context.path(name);
            if (!field.isTextual() || !field.asText().matches("[a-zA-Z0-9_.:@/-]{1,160}")) throw TrialException.unauthorized();
            values.put(name, field.asText());
        }
        if (!key.getAssistantIds().contains(values.get("assistantId")) || !key.getAudiences().contains(values.get("audience"))
                || !Set.of("anonymous", "customer", "employee").contains(values.get("audience"))
                || !key.getChannels().contains(values.get("channel"))) throw TrialException.unauthorized();
        String operation = TrialServiceAuth.sha256(values.get("operationId"));
        String fingerprint = TrialServiceAuth.sha256(String.join("\n", "knowdo-context-v1", capability,
                request.getMethod(), request.getRequestURI(), TrialServiceAuth.sha256(body), values.toString()));
        try {
            jdbc.update("INSERT INTO crm_trial_connector_operation(issuer,operation_hash,request_hash,created_at) VALUES(?,?,?,?)",
                    key.getIssuer(), operation, fingerprint, Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException duplicate) {
            String prior = jdbc.queryForObject("SELECT request_hash FROM crm_trial_connector_operation WHERE issuer=? AND operation_hash=?",
                    String.class, key.getIssuer(), operation);
            if (!fingerprint.equals(prior)) throw TrialException.conflict();
        }
        return new TrialIdentity(key.getIssuer(), values.get("actorId"), "", "", "knowdo-" + operation, "");
    }
    private static Iterable<String> iterable(Iterator<String> names) { return () -> names; }
    private static String header(HttpServletRequest request, String name, int max) {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() != 1 || values.get(0).isEmpty() || values.get(0).length() > max
                || values.get(0).chars().anyMatch(c -> c < 32 || c == 127)) throw TrialException.unauthorized();
        return values.get(0);
    }
}
