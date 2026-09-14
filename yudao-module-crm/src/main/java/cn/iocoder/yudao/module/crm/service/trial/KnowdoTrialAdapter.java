package cn.iocoder.yudao.module.crm.service.trial;

import java.util.Map;

/** Deterministic internal API, not a conversation or Agent invocation. */
public interface KnowdoTrialAdapter {
    enum LookupState { COMPLETE, ABSENT, PENDING }
    record Result(LookupState state, Map<String, String> resourceIds) { }
    /** ABSENT must be authoritative; transport errors/404 on an unknown route are not ABSENT. */
    Result lookup(TrialStore.Application application, String step);
    /** Must deduplicate by application.id + step and enforce an application-wide revocation tombstone. */
    Result ensure(TrialStore.Application application, String step, Map<String, String> mgsResources);
}
