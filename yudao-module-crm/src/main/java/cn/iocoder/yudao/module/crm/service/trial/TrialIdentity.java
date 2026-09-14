package cn.iocoder.yudao.module.crm.service.trial;

/** Produced only by the service signature verifier, never bound from a model request. */
public record TrialIdentity(String issuer, String subjectId, String verifiedEmail, String confirmation, String idempotencyKey) {
    @Override public String toString() { return "TrialIdentity[REDACTED]"; }
    public String identityHash() { return TrialServiceAuth.sha256(issuer + "\n" + subjectId); }
}
