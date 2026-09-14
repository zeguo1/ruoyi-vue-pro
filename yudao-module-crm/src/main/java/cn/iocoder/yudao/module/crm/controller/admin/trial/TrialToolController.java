package cn.iocoder.yudao.module.crm.controller.admin.trial;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.crm.controller.admin.trial.vo.TrialApplicationReqVO;
import cn.iocoder.yudao.module.crm.controller.admin.trial.vo.TrialGuideRespVO;
import cn.iocoder.yudao.module.crm.controller.admin.trial.vo.TrialStatusRespVO;
import cn.iocoder.yudao.module.crm.controller.admin.trial.vo.TrialSubmitReqVO;
import cn.iocoder.yudao.module.crm.service.trial.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/crm/trial-tool")
@Tag(name = "知办可信服务 - 试用开户工具")
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
@RequiredArgsConstructor
public class TrialToolController {
    private final TrialProperties properties;
    private final TrialStore store;
    private final TrialOrchestrator orchestrator;

    @PostMapping("/submit") @PermitAll @TenantIgnore @ApiAccessLog(enable = false) @TrialCapability("TOOLS")
    @Operation(operationId = "submit_trial_application", summary = "保存本人试用申请并关联运营线索",
            description = "仅限可信服务 HMAC 请求。联系方式来自已验证身份，不接受自报身份、租户、角色、套餐或期限。code=0 不等于开户完成。",
            extensions = @Extension(name = "x-mgs-trial", properties = @ExtensionProperty(name = "readOnly", value = "false", parseValue = true)))
    public CommonResult<TrialStatusRespVO> submit(@Valid @RequestBody TrialSubmitReqVO body, HttpServletRequest request) {
        TrialIdentity identity = identity(request);
        if (!identity.verifiedEmail().matches("[^\\s@]{1,64}@[^\\s@]+\\.[^\\s@]+")
                || !identity.idempotencyKey().matches("[a-zA-Z0-9_.:-]{16,128}")) { throw TrialException.unauthorized(); }
        TrialProperties.Policy policy = properties.newPolicy();
        TrialStore.Application app = store.submit(identity, identity.idempotencyKey(), body.getTeam(), body.getContactName(),
                body.getScenario(), policy, properties.getMaxApplications());
        orchestrator.advance(app.id()); // Unconfirmed applications can only execute the local CRM step.
        return CommonResult.success(view(store.owned(app.id(), identity)));
    }

    @PostMapping("/create-accounts") @PermitAll @TenantIgnore @ApiAccessLog(enable = false) @TrialCapability("TOOLS")
    @Operation(operationId = "create_trial_accounts", summary = "确认申请并受理双侧开户",
            description = "确认凭据由可信服务对当前申请出具并纳入签名。只持久化确认和任务，后台逐步执行。以 data.accountReady=true 判断全部就绪。")
    public CommonResult<TrialStatusRespVO> create(@Valid @RequestBody TrialApplicationReqVO body, HttpServletRequest request) {
        properties.newPolicy(); // Missing new-enrollment configuration must fail closed.
        store.confirm(body.getApplicationId(), identity(request));
        return CommonResult.success(view(store.owned(body.getApplicationId(), identity(request))));
    }

    @PostMapping("/status") @PermitAll @TenantIgnore @ApiAccessLog(enable = false) @TrialCapability("TOOLS")
    @Operation(operationId = "get_trial_status", summary = "只读查询本人申请状态",
            description = "不会因为查询而重放开户。code=0 表示状态查询成功，data.accountReady=true 才是账号全部就绪。",
            extensions = @Extension(name = "x-mgs-trial", properties = @ExtensionProperty(name = "readOnly", value = "true", parseValue = true)))
    public CommonResult<TrialStatusRespVO> status(@Valid @RequestBody TrialApplicationReqVO body, HttpServletRequest request) {
        return CommonResult.success(view(store.owned(body.getApplicationId(), identity(request))));
    }

    @PostMapping("/guide") @PermitAll @TenantIgnore @ApiAccessLog(enable = false) @TrialCapability("TOOLS")
    @Operation(operationId = "get_trial_guide", summary = "只读取得本人真实可用的演示指南",
            description = "仅账号就绪后返回样例客户引用。业务办理通过 Agent，MGS 页面用于查看结果。",
            extensions = @Extension(name = "x-mgs-trial", properties = @ExtensionProperty(name = "readOnly", value = "true", parseValue = true)))
    public CommonResult<TrialGuideRespVO> guide(@Valid @RequestBody TrialApplicationReqVO body, HttpServletRequest request) {
        TrialStore.Application app = store.owned(body.getApplicationId(), identity(request));
        boolean ready = ready(app);
        Map<String, String> resources = orchestrator.resources(app.id());
        return CommonResult.success(new TrialGuideRespVO(ready, app.id(),
                ready ? "通过安全卡片完成激活和公众号绑定，然后对栖云AI 软件工厂说：查询我的演示客户；为该客户新增一条跟进。办理后可在 MGS 查看结果。" : "请先等待两侧账号、授权和演示数据就绪。",
                ready ? resources.getOrDefault("customerId", "") : "",
                ready ? app.policy().mgsLoginUrl() : ""));
    }

    private TrialStatusRespVO view(TrialStore.Application app) {
        boolean ready = ready(app);
        return new TrialStatusRespVO(app.id(), app.status(), ready, app.expiresAt().toString(),
                store.steps(app.id()).stream().map(s -> new TrialStatusRespVO.StepProgress(s.name(), s.state(), s.errorCode())).toList(),
                ready ? "领取安全卡片并绑定公众号" : (app.confirmedAt() == null ? "确认开通" : "等待后台处理或联系运营人员"),
                ready ? "TRIAL_ACTIVATION_AND_WECHAT_BINDING" : null,
                ready ? store.step(app.id(), "DELIVERY").result().get("deliveryRef") : null);
    }
    private boolean ready(TrialStore.Application app) { return "READY".equals(app.status()) && app.expiresAt().isAfter(Instant.now()); }
    private TrialIdentity identity(HttpServletRequest request) {
        Object value = request.getAttribute(TrialRequestAdvice.IDENTITY_ATTRIBUTE);
        if (!(value instanceof TrialIdentity identity)) { throw TrialException.unauthorized(); }
        return identity;
    }
}
