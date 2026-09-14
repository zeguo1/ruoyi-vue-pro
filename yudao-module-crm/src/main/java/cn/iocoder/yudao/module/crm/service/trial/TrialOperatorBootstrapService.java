package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.system.controller.admin.tenant.vo.packages.TenantPackageSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.tenant.vo.tenant.TenantSaveReqVO;
import cn.iocoder.yudao.module.system.service.permission.MenuService;
import cn.iocoder.yudao.module.system.service.tenant.TenantPackageService;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.dynamic.datasource.tx.TransactionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Creates the separate corporate tenant through the existing tenant/user/role services, never tenant 1.
 * Internal package is a technical menu snapshot; no visitor-facing plan selection or trial package is introduced. */
@Service
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
@RequiredArgsConstructor
public class TrialOperatorBootstrapService {
    private static final String NAME = "栖云";
    private static final String PACKAGE_NAME = "栖云企业内部权限";
    private final TrialOperatorBootstrapProperties properties;
    private final JdbcTemplate jdbc;
    private final TenantService tenants;
    private final TenantPackageService packages;
    private final MenuService menus;
    private final AdminUserService users;
    public record Result(long operatorTenantId, long ownerUserId, long internalPackageId) { }

    @DSTransactional
    public Result initialize() {
        properties.validate();
        // TenantService.createTenant uses DSTransactional. Join that transaction family for the guard,
        // registry and local service writes; mixing an outer Spring transaction would break its commit boundary.
        if (!(jdbc.getDataSource() instanceof DynamicRoutingDataSource) || TransactionContext.getXID() == null
                || TransactionSynchronizationManager.isActualTransactionActive()) {
            throw TrialException.unavailable();
        }
        jdbc.queryForObject("SELECT id FROM crm_trial_guard WHERE id=1 FOR UPDATE", Integer.class);
        String fingerprint = TrialServiceAuth.sha256(String.join("\n", NAME, properties.getContactName(), properties.getUsername(),
                properties.getExpireTime().toString(), properties.getAccountCount().toString(),
                properties.getMenuIds().stream().sorted().map(Object::toString).collect(java.util.stream.Collectors.joining(","))));
        var saved = jdbc.queryForList("SELECT * FROM crm_trial_operator_setup WHERE setup_key='qiyun'");
        if (!saved.isEmpty()) {
            var row = saved.get(0);
            if (!fingerprint.equals(row.get("config_hash"))) { throw conflict(); }
            var result = new Result(((Number) row.get("tenant_id")).longValue(), ((Number) row.get("owner_user_id")).longValue(),
                    ((Number) row.get("package_id")).longValue());
            validateResult(result);
            return result; // Does not reset the administrator password or overwrite an existing role.
        }
        if (tenants.getTenantByName(NAME) != null) { throw conflict(); }
        var selected = menus.getMenuList(properties.getMenuIds());
        if (selected.size() != properties.getMenuIds().size()
                || selected.stream().anyMatch(menu -> !Integer.valueOf(0).equals(menu.getStatus()))
                || !selected.stream().map(menu -> menu.getPermission()).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet())
                .containsAll(Set.of("crm:trial:query", "crm:trial:recover", "crm:clue:query"))
                || selected.stream().anyMatch(menu -> menu.getParentId() != null && menu.getParentId() != 0
                && !properties.getMenuIds().contains(menu.getParentId()))) { throw TrialException.unavailable(); }
        var internalPackage = new TenantPackageSaveReqVO();
        internalPackage.setName(PACKAGE_NAME); internalPackage.setStatus(0);
        internalPackage.setRemark("企业内部运营权限快照，不是访客体验套餐"); internalPackage.setMenuIds(properties.getMenuIds());
        Long packageId = packages.createTenantPackage(internalPackage);
        var request = new TenantSaveReqVO();
        request.setName(NAME); request.setContactName(properties.getContactName()); request.setStatus(0);
        request.setPackageId(packageId); request.setExpireTime(properties.getExpireTime()); request.setAccountCount(properties.getAccountCount());
        request.setUsername(properties.getUsername()); request.setPassword(properties.getPassword());
        Long tenantId = tenants.createTenant(request);
        var tenant = tenants.getTenant(tenantId);
        if (tenant == null || tenant.getContactUserId() == null) { throw conflict(); }
        var result = new Result(tenantId, tenant.getContactUserId(), packageId);
        validateResult(result);
        jdbc.update("INSERT INTO crm_trial_operator_setup(setup_key,config_hash,tenant_id,owner_user_id,package_id,created_at) VALUES('qiyun',?,?,?,?,?)",
                fingerprint, result.operatorTenantId(), result.ownerUserId(), result.internalPackageId(), Timestamp.from(Instant.now()));
        return result;
    }
    private void validateResult(Result result) {
        if (result.operatorTenantId() <= 1 || result.ownerUserId() <= 0) { throw conflict(); }
        tenants.validTenant(result.operatorTenantId());
        var tenant = tenants.getTenant(result.operatorTenantId());
        if (tenant == null || !NAME.equals(tenant.getName()) || !Objects.equals(tenant.getPackageId(), result.internalPackageId())
                || !Objects.equals(tenant.getContactUserId(), result.ownerUserId())) { throw conflict(); }
        TenantUtils.execute(result.operatorTenantId(), () -> users.validateUserList(List.of(result.ownerUserId())));
    }
    private static cn.iocoder.yudao.framework.common.exception.ServiceException conflict() {
        return TrialException.error(13, "栖云初始化记录或既有租户不一致，请由内部管理员核验；不会自动接管或重建");
    }
}
