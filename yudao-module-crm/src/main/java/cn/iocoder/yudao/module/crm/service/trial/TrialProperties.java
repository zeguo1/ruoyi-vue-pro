package cn.iocoder.yudao.module.crm.service.trial;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;
import java.util.Set;

@Data
@Component
@ConfigurationProperties(prefix = "mgs.trial")
public class TrialProperties {
    private boolean storageEnabled;
    private boolean enabled;
    private String environment;
    private Long demoTenantId;
    private Long operatorTenantId;
    private Long ownerUserId;
    private Integer durationDays;
    private Integer maxApplications;
    private String knowdoBaseUrl;
    private String outboundKeyId;
    @lombok.ToString.Exclude
    private String outboundSecret;
    private String mgsLoginUrl;
    private String oauthClientId;
    private Map<String, ServiceKey> keys = Map.of();
    private LoginDelivery loginDelivery = new LoginDelivery();

    @Data
    public static class LoginDelivery {
        private String activeKeyId;
        @lombok.ToString.Exclude
        private Map<String, String> encryptionKeys = Map.of();

        public boolean ready() {
            try { return activeKeyId != null && activeKeyId.matches("[a-zA-Z0-9_-]{1,64}")
                    && java.util.Base64.getDecoder().decode(encryptionKeys.get(activeKeyId)).length == 32; }
            catch (RuntimeException ex) { return false; }
        }
    }

    @Data
    public static class ServiceKey {
        private String issuer;
        @lombok.ToString.Exclude
        private String secret;
        private Set<String> capabilities = Set.of();
    }

    /** Immutable per-application choices; changes to defaults must not repoint existing accounts. */
    public record Policy(String environment, long operatorTenantId, long ownerUserId,
                         long demoTenantId, int durationDays, String mgsLoginUrl, String knowdoBaseUrl, String oauthClientId) { }

    public Policy newPolicy() {
        if (!enabled || loginDelivery == null || !loginDelivery.ready() || environment == null || environment.isBlank()
                || operatorTenantId == null || ownerUserId == null || demoTenantId == null
                || operatorTenantId <= 0 || ownerUserId <= 0 || demoTenantId <= 0
                || operatorTenantId.equals(demoTenantId)
                || durationDays == null || durationDays < 1 || durationDays > 90
                || oauthClientId == null || oauthClientId.isBlank()
                || maxApplications == null || maxApplications < 1 || !https(knowdoBaseUrl)
                || !https(mgsLoginUrl) || outboundKeyId == null || outboundKeyId.isBlank()
                || outboundSecret == null || outboundSecret.length() < 32) {
            throw TrialException.unavailable();
        }
        return new Policy(environment, operatorTenantId, ownerUserId, demoTenantId, durationDays, mgsLoginUrl, knowdoBaseUrl, oauthClientId);
    }

    private static boolean https(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getFragment() == null && uri.getQuery() == null;
        } catch (RuntimeException ex) { return false; }
    }
}
