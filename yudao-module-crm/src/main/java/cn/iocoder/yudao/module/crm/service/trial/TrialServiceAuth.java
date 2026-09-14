package cn.iocoder.yudao.module.crm.service.trial;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.HexFormat;

@Service
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
@RequiredArgsConstructor
public class TrialServiceAuth {
    private final TrialProperties properties;
    private final JdbcTemplate jdbc;

    // This protocol is exclusively service-to-service over TLS. No header is a tool input.
    public TrialIdentity verify(HttpServletRequest request, byte[] body, String capability) {
        // Trust only the container's secure-request decision, never caller-supplied forwarding headers.
        if (!request.isSecure() || body.length > 16_384 || request.getQueryString() != null
                || request.getHeader("tenant-id") != null || request.getHeader("visit-tenant-id") != null) {
            throw TrialException.unauthorized();
        }
        String keyId = header(request, "Key", 64);
        String timestamp = header(request, "Timestamp", 20);
        String nonce = header(request, "Nonce", 64);
        String subject = header(request, "Subject", 128);
        String verified = header(request, "Verified", 5);
        String email = header(request, "Email", 254);
        String confirmation = header(request, "Confirmation", 128);
        String idempotency = header(request, "Idempotency", 128);
        String signature = header(request, "Signature", 64);
        TrialProperties.ServiceKey key = properties.getKeys().get(keyId);
        if (key == null || key.getSecret() == null || key.getSecret().length() < 32
                || key.getIssuer() == null || !key.getIssuer().matches("[a-zA-Z0-9_-]{1,64}")
                || !key.getCapabilities().contains(capability) || !"true".equals(verified)
                || !nonce.matches("[a-zA-Z0-9_-]{16,64}") || subject.isBlank()) {
            throw TrialException.unauthorized();
        }
        long seconds;
        try { seconds = Long.parseLong(timestamp); } catch (NumberFormatException e) { throw TrialException.unauthorized(); }
        long now = Instant.now().getEpochSecond();
        if (seconds < now - 120 || seconds > now + 120) { throw TrialException.unauthorized(); }
        String canonical = String.join("\n", "mgs-trial-v1", keyId, timestamp, nonce, request.getMethod(),
                request.getRequestURI(), sha256(body), subject, verified, email, confirmation, idempotency);
        if (!MessageDigest.isEqual(hmac(key.getSecret(), canonical).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII))) {
            throw TrialException.unauthorized();
        }
        try {
            jdbc.update("INSERT INTO crm_trial_nonce(key_id,nonce,expires_at) VALUES(?,?,?)",
                    keyId, nonce, Timestamp.from(Instant.ofEpochSecond(now + 300)));
        } catch (DuplicateKeyException e) { throw TrialException.replay(); }
        return new TrialIdentity(key.getIssuer(), subject, email, confirmation, idempotency);
    }

    private static String header(HttpServletRequest request, String name, int max) {
        String value = request.getHeader("X-Mgs-Trial-" + name);
        if (value == null) { value = ""; }
        if (value.length() > max || value.chars().anyMatch(c -> c < 32 || c == 127)
                || java.util.Collections.list(request.getHeaders("X-Mgs-Trial-" + name)).size() > 1) {
            throw TrialException.unauthorized();
        }
        return value;
    }

    public static String sha256(String text) { return sha256(text.getBytes(StandardCharsets.UTF_8)); }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String hmac(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}
