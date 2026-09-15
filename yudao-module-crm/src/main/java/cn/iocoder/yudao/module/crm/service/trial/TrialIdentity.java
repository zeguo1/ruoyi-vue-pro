package cn.iocoder.yudao.module.crm.service.trial;

/** Produced only by trusted service authentication and server-side proof/consent checks, never bound from a model request. */
public record TrialIdentity(String issuer, String subjectId, String verifiedEmail, String confirmation, String idempotencyKey,
                            String verificationToken) {
    /** Existing applications and internal fixtures retain their original identity; new HTTP submissions require an MGS proof. */
    public TrialIdentity(String issuer, String subjectId, String verifiedEmail, String confirmation, String idempotencyKey) {
        this(issuer, subjectId, verifiedEmail, confirmation, idempotencyKey, "");
    }
    @Override public String toString() { return "TrialIdentity[REDACTED]"; }
    public String identityHash() { return TrialServiceAuth.sha256(issuer + "\n" + subjectId); }
}
