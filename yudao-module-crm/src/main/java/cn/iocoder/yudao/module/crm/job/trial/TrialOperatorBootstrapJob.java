package cn.iocoder.yudao.module.crm.job.trial;

import cn.iocoder.yudao.framework.quartz.core.handler.JobHandler;
import cn.iocoder.yudao.module.crm.service.trial.TrialException;
import cn.iocoder.yudao.module.crm.service.trial.TrialOperatorBootstrapService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Internal setup job, not exposed in the public Agent tool catalog. Do not schedule periodically. */
@Component
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
@RequiredArgsConstructor
public class TrialOperatorBootstrapJob implements JobHandler {
    private final TrialOperatorBootstrapService bootstrap;
    @Override public String execute(String param) {
        if (!"initialize-qiyun".equals(param)) { throw TrialException.unavailable(); }
        var result = bootstrap.initialize();
        return "栖云初始化已核对：operator-tenant-id=" + result.operatorTenantId()
                + ", owner-user-id=" + result.ownerUserId() + ", internal-package-id=" + result.internalPackageId();
    }
}
