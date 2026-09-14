package cn.iocoder.yudao.module.crm.service.trial;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.crm.controller.admin.clue.vo.CrmClueSaveReqVO;
import cn.iocoder.yudao.module.crm.service.clue.CrmClueService;
import cn.iocoder.yudao.module.crm.service.customer.CrmCustomerService;
import cn.iocoder.yudao.module.crm.service.customer.bo.CrmCustomerCreateReqBO;
import cn.iocoder.yudao.module.system.controller.admin.permission.vo.role.RoleSaveReqVO;
import cn.iocoder.yudao.module.system.controller.admin.user.vo.user.UserSaveReqVO;
import cn.iocoder.yudao.module.system.dal.dataobject.permission.MenuDO;
import cn.iocoder.yudao.module.system.enums.permission.DataScopeEnum;
import cn.iocoder.yudao.module.system.enums.permission.RoleTypeEnum;
import cn.iocoder.yudao.module.system.service.permission.MenuService;
import cn.iocoder.yudao.module.system.service.permission.PermissionService;
import cn.iocoder.yudao.module.system.service.permission.RoleService;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
@RequiredArgsConstructor
public class MgsTrialProvisioner implements TrialLocalProvisioner {
    private final CrmClueService clues;
    private final CrmCustomerService customers;
    private final TenantService tenants;
    private final AdminUserService users;
    private final RoleService roles;
    private final PermissionService permissions;
    private final MenuService menus;
    private final TrialOAuthGateway oauth;
    private final JdbcTemplate jdbc;
    private final TrialAuthorizationService authorizations;
    private final TrialLoginDeliveryService loginDelivery;

    @Override
    public Map<String, String> execute(TrialStore.Application app, String step, Map<String, String> resources) {
        return switch (step) {
            case "CRM" -> createClue(app);
            case "MGS" -> createAccount(app);
            case "MGS_GRANT" -> authorizations.prepare(app);
            case "DEMO" -> createDemo(app, resources);
            case "MGS_REVOKE" -> revoke(app, resources);
            default -> throw new IllegalArgumentException("Unknown local trial step");
        };
    }

    private Map<String, String> createClue(TrialStore.Application app) {
        return TenantUtils.execute(app.policy().operatorTenantId(), () -> {
            users.validateUserList(List.of(app.policy().ownerUserId()));
            CrmClueSaveReqVO req = new CrmClueSaveReqVO();
            req.setName(app.team());
            req.setOwnerUserId(app.policy().ownerUserId());
            req.setEmail(app.verifiedEmail());
            req.setDescription("知办官网试用申请；联系人：" + app.contactName() + "；场景：客户跟进演示");
            req.setRemark("申请编号：" + app.id() + "；开通不代表成交");
            return Map.of("clueId", clues.createClue(req).toString());
        });
    }

    private Map<String, String> createAccount(TrialStore.Application app) {
        long tenantId = app.policy().demoTenantId();
        tenants.validTenant(tenantId);
        if (tenantId == app.policy().operatorTenantId()) { throw TrialException.unavailable(); }
        Set<Long> allowedMenus = allowedMenus();
        String suffix = app.id().replace("-", "").substring(0, 20);
        return TenantUtils.execute(tenantId, () -> {
            RoleSaveReqVO role = new RoleSaveReqVO();
            // RoleService enforces unique names as well as codes within a tenant.
            role.setName("客户跟进体验-" + suffix); role.setCode("mgs_trial_" + suffix); role.setSort(100); role.setStatus(0);
            Long roleId = roles.createRole(role, RoleTypeEnum.CUSTOM.getType());
            roles.updateRoleDataScope(roleId, DataScopeEnum.SELF.getScope(), Set.of());
            permissions.assignRoleMenu(roleId, allowedMenus);
            UserSaveReqVO user = new UserSaveReqVO();
            user.setUsername("tu" + suffix); user.setNickname(app.contactName()); user.setPassword(TrialLoginVault.newPassword());
            // Contact stays in the operator tenant; no reuse/merge by untrusted mobile or domain.
            Long userId = users.createUser(user);
            permissions.assignUserRole(userId, Set.of(roleId));
            jdbc.update("INSERT INTO crm_trial_account(application_id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",
                    app.id(), tenantId, userId, roleId);
            loginDelivery.prepare(app, userId, user.getPassword());
            return Map.of("tenantId", Long.toString(tenantId), "userId", userId.toString(), "roleId", roleId.toString(),
                    "username", user.getUsername());
        });
    }

    private Set<Long> allowedMenus() {
        Set<Long> result = new HashSet<>();
        Set<String> businessPermissions = Set.of("crm:trial-business:query", "crm:trial-business:follow-up");
        for (String permission : businessPermissions) {
            boolean found = false;
            for (Long id : menus.getMenuIdListByPermissionFromCache(permission)) {
                MenuDO menu = menus.getMenu(id);
                if (menu != null && Integer.valueOf(0).equals(menu.getStatus())) {
                    found = true;
                    result.add(id);
                    Long parent = menu.getParentId();
                    while (parent != null && parent != 0 && !result.contains(parent)) {
                        MenuDO ancestor = menus.getMenu(parent);
                        if (ancestor == null || !Integer.valueOf(0).equals(ancestor.getStatus())
                                || (ancestor.getPermission() != null && !ancestor.getPermission().isBlank()
                                && !businessPermissions.contains(ancestor.getPermission()))) {
                            throw TrialException.unavailable();
                        }
                        result.add(parent); parent = ancestor.getParentId();
                    }
                }
            }
            if (!found) { throw TrialException.unavailable(); }
        }
        return result;
    }

    private Map<String, String> createDemo(TrialStore.Application app, Map<String, String> resources) {
        long tenant = Long.parseLong(resources.get("tenantId"));
        long user = Long.parseLong(resources.get("userId"));
        if (tenant == app.policy().operatorTenantId()) { throw new IllegalStateException("Demo tenant collision"); }
        return TenantUtils.execute(tenant, () -> {
            CrmCustomerCreateReqBO customer = new CrmCustomerCreateReqBO();
            customer.setName("演示客户 · 星河文具（虚构）"); customer.setOwnerUserId(user);
            customer.setFollowUpStatus(false); customer.setLockStatus(true); customer.setDealStatus(false);
            customer.setRemark("仅供客户查询和跟进体验，无真实联系方式、附件或交易。申请：" + app.id());
            return Map.of("customerId", customers.createCustomer(customer, user).toString());
        });
    }

    private Map<String, String> revoke(TrialStore.Application app, Map<String, String> resources) {
        if (!resources.containsKey("tenantId")) { return Map.of("revoked", "true"); }
        return TenantUtils.execute(Long.parseLong(resources.get("tenantId")), () -> {
            long userId = Long.parseLong(resources.get("userId"));
            permissions.assignUserRole(userId, Set.of());
            users.updateUserStatus(userId, 1);
            oauth.revoke(userId);
            loginDelivery.revoke(app.id());
            return Map.of("revoked", "true");
        });
    }

}
