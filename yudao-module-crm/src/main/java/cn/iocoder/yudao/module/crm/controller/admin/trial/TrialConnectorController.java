package cn.iocoder.yudao.module.crm.controller.admin.trial;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.crm.controller.admin.trial.vo.*;
import cn.iocoder.yudao.module.crm.service.trial.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** Only the four model-visible commands. Private safe-card operations use separately scoped service keys. */
@RestController @RequiredArgsConstructor
@RequestMapping(value = "/crm/trial-connector", consumes = "application/json", produces = "application/json")
@Tag(name = "知办标准连接器 - 本人试用申请")
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
public class TrialConnectorController {
    private final TrialToolController tools;
    private final TrialConnectorConsent consent;
    private final TrialSmsVerificationService verification;

    @PostMapping("/submit") @PermitAll @TenantIgnore @ApiAccessLog(enable = false) @TrialConnectorCapability("TOOLS")
    @Operation(operationId = "connector_submit_trial_application", summary = "登记本人试用申请",
            description = "使用专用服务 Bearer 与知办自动注入的可信调用身份。先由安全卡片完成 MGS 手机号验证，后端自行读取验证记录；只提交团队、联系人和场景，不提交手机号、验证码或凭据。登记不会自动确认开户。")
    public CommonResult<TrialStatusRespVO> submit(@Valid @RequestBody TrialSubmitReqVO body, HttpServletRequest request) {
        request.setAttribute(TrialRequestAdvice.IDENTITY_ATTRIBUTE, verification.connectorSubmissionIdentity(identity(request)));
        return tools.submit(body, request);
    }

    @PostMapping("/create-accounts") @PermitAll @TenantIgnore @ApiAccessLog(enable = false) @TrialConnectorCapability("TOOLS")
    @Operation(operationId = "connector_create_trial_accounts", summary = "受理本人已确认申请的开户",
            description = "必须先由用户在安全卡片明确确认当前申请，MGS 核验已保存的确认记录；工具调用及可信调用身份本身不代表用户同意。code=0 为受理成功，data.accountReady=true 才表示两侧账号就绪。")
    public CommonResult<TrialStatusRespVO> create(@Valid @RequestBody TrialApplicationReqVO body, HttpServletRequest request) {
        request.setAttribute(TrialRequestAdvice.IDENTITY_ATTRIBUTE, consent.activate(body.getApplicationId(), identity(request)));
        return tools.create(body, request);
    }

    @PostMapping("/status") @PermitAll @TenantIgnore @ApiAccessLog(enable = false) @TrialConnectorCapability("TOOLS")
    @Operation(operationId = "connector_get_trial_status", summary = "查询本人试用申请状态",
            description = "只读，不重放开户；code=0 只代表查询成功，data.accountReady=true 才代表两侧账号就绪。")
    public CommonResult<TrialStatusRespVO> status(@Valid @RequestBody TrialApplicationReqVO body, HttpServletRequest request) {
        return tools.status(body, request);
    }

    @PostMapping("/guide") @PermitAll @TenantIgnore @ApiAccessLog(enable = false) @TrialConnectorCapability("TOOLS")
    @Operation(operationId = "connector_get_trial_guide", summary = "查询本人试用指南",
            description = "只读查询，账号就绪后返回本人演示客户引用，不返回密码、访问令牌或安全交付凭据。")
    public CommonResult<TrialGuideRespVO> guide(@Valid @RequestBody TrialApplicationReqVO body, HttpServletRequest request) {
        return tools.guide(body, request);
    }
    static TrialIdentity identity(HttpServletRequest request) {
        Object value = request.getAttribute(TrialRequestAdvice.IDENTITY_ATTRIBUTE);
        if (!(value instanceof TrialIdentity identity)) throw TrialException.unauthorized();
        return identity;
    }
}
