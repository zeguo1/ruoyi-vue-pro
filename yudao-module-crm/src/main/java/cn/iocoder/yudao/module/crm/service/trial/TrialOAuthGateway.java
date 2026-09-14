package cn.iocoder.yudao.module.crm.service.trial;

/** All secrets remain behind the dedicated authenticated service endpoint. */
public interface TrialOAuthGateway {
    void revoke(long userId);
    long create(long userId, String clientId);
    Credential resolve(long userId, long refreshTokenId, String clientId);

    record Credential(String accessToken, String expiresAt) {
        @Override public String toString() { return "TrialCredential[REDACTED]"; }
    }
}
