package cn.iocoder.yudao.module.crm.service.trial;

import java.util.Map;

/** Local effects execute in the same bounded transaction as their durable step marker. */
public interface TrialLocalProvisioner {
    Map<String, String> execute(TrialStore.Application application, String step, Map<String, String> resources);
}
