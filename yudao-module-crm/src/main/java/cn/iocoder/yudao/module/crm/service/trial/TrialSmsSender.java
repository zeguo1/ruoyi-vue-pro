package cn.iocoder.yudao.module.crm.service.trial;

/** Uses the existing MGS SMS transport. Implementations must not log or persist the plaintext code. */
public interface TrialSmsSender {
    void send(String mobile, String code, String templateCode);
}
