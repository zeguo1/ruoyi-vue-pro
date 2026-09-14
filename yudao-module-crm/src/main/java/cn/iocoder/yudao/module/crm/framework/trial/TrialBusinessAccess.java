package cn.iocoder.yudao.module.crm.framework.trial;

import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.crm.service.trial.TrialException;
import cn.iocoder.yudao.module.crm.service.trial.TrialStore;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
@RequiredArgsConstructor
public class TrialBusinessAccess {
    private final JdbcTemplate jdbc;
    private final TrialStore store;
    private static final Set<String> ALLOWED = Set.of(
            "GET /admin-api/crm/trial-business/customer", "GET /admin-api/crm/trial-business/follow-ups",
            "GET /admin-api/crm/trial-business/follow-up-types", "POST /admin-api/crm/trial-business/follow-up",
            "GET /admin-api/system/auth/get-permission-info", "GET /admin-api/system/user/profile/get",
            "POST /admin-api/system/auth/logout");

    public String applicationForUser(Long userId) {
        if (userId == null) { return null; }
        List<String> ids = jdbc.queryForList("SELECT application_id FROM crm_trial_account WHERE user_id=?", String.class, userId);
        return ids.isEmpty() ? null : ids.get(0);
    }
    public TrialStore.Application requireCurrent() {
        String id = applicationForUser(SecurityFrameworkUtils.getLoginUserId());
        if (id == null) { throw TrialException.notFound(); }
        return requireActive(id, TenantContextHolder.getTenantId());
    }
    public TrialStore.Application requireActive(String id, Long tenantId) {
        TrialStore.Application app = store.get(id);
        if (app == null || tenantId == null || tenantId != app.policy().demoTenantId()
                || !"READY".equals(app.status()) || !app.expiresAt().isAfter(Instant.now())) {
            throw TrialException.error(8, "体验账号未就绪或已到期");
        }
        return app;
    }
    public void checkRoute(Long userId, Long tenantId, String method, String path) {
        String appId = applicationForUser(userId);
        if (appId == null) { return; }
        if ("POST /admin-api/system/auth/logout".equals(method + " " + path)) { return; }
        requireActive(appId, tenantId);
        // Shared demo tenant contains pre-existing data. Trial accounts may only use dedicated scoped APIs.
        // Also blocks exports, upload/download, role management and all unrelated business APIs.
        if (!ALLOWED.contains(method + " " + path)) { throw TrialException.error(9, "体验账号无此接口权限"); }
    }
}
