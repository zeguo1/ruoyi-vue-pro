package cn.iocoder.yudao.module.crm.controller.admin.trial;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.crm.controller.admin.trial.vo.TrialApplicationReqVO;
import cn.iocoder.yudao.module.crm.service.trial.TrialException;
import cn.iocoder.yudao.module.crm.service.trial.TrialStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/crm/trial-operations")
@Tag(name = "管理后台 - 试用运营")
@Validated
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
@RequiredArgsConstructor
public class TrialOperationsController {
    private final TrialStore store;
    private final JdbcTemplate jdbc;
    public record Summary(String applicationId, String team, String contactName, String status, String expiresAt,
                          String crmClueId, String boundAt, String firstBusinessAt) { }
    public record Detail(Summary application, List<TrialStore.Step> steps) { }

    @GetMapping("/page") @PreAuthorize("@ss.hasPermission('crm:trial:query')")
    @Operation(summary = "分页查询本运营租户的试用申请", description = "不返回可信身份、联系方式或任何凭据。联系方式请按现有 CRM 权限查看关联线索。")
    public CommonResult<PageResult<Summary>> page(@RequestParam(defaultValue = "1") @Min(1) int pageNo,
                                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        Long tenantId = TenantContextHolder.getRequiredTenantId();
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM crm_trial_application WHERE operator_tenant_id=?", Long.class, tenantId);
        List<String> ids = jdbc.queryForList("SELECT id FROM crm_trial_application WHERE operator_tenant_id=? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                String.class, tenantId, pageSize, (long) (pageNo - 1) * pageSize);
        return CommonResult.success(new PageResult<>(ids.stream().map(this::summary).toList(), total));
    }
    @GetMapping("/get") @PreAuthorize("@ss.hasPermission('crm:trial:query')")
    @Operation(summary = "查看本人运营租户的申请与逐步执行结果")
    public CommonResult<Detail> get(@RequestParam String applicationId) {
        requireOperator(applicationId);
        return CommonResult.success(new Detail(summary(applicationId), store.steps(applicationId)));
    }
    @PostMapping("/recover") @PreAuthorize("@ss.hasPermission('crm:trial:recover')") @ApiAccessLog(requestEnable = false)
    @Operation(summary = "请求后台恢复申请未完成步骤", description = "不跳过用户确认，不重建已完成资源。外部不确定状态先核对；由 TrialMaintenanceJob 续办。返回 true 只表示恢复请求已受理。")
    public CommonResult<Boolean> recover(@Valid @RequestBody TrialApplicationReqVO request) {
        requireOperator(request.getApplicationId());
        // Pending steps are durable; touching rotates the queue without pretending a step has succeeded.
        store.touch(request.getApplicationId());
        return CommonResult.success(true);
    }
    private void requireOperator(String id) {
        var app = store.get(id);
        if (app == null || app.policy().operatorTenantId() != TenantContextHolder.getRequiredTenantId()) { throw TrialException.notFound(); }
    }
    private Summary summary(String id) {
        var app = store.get(id);
        var events = jdbc.queryForMap("SELECT bound_at,first_business_at FROM crm_trial_application WHERE id=?", id);
        return new Summary(app.id(), app.team(), app.contactName(), app.status(), app.expiresAt().toString(),
                store.step(id, "CRM").result().get("clueId"),
                events.get("bound_at") == null ? null : events.get("bound_at").toString(),
                events.get("first_business_at") == null ? null : events.get("first_business_at").toString());
    }
}
