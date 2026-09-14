package cn.iocoder.yudao.module.crm.controller.admin.trial;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.crm.controller.admin.trial.vo.TrialApplicationReqVO;
import cn.iocoder.yudao.module.crm.service.trial.*;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** Credential transport is intentionally absent from the Agent's OpenAPI/tool catalog. */
@Hidden
@RestController
@RequestMapping("/crm/trial-internal")
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
@RequiredArgsConstructor
public class TrialAuthorizationController {
    private final TrialAuthorizationService authorizations;

    @PostMapping("/authorization") @PermitAll @TenantIgnore
    @TrialCapability("AUTHORIZATION") @ApiAccessLog(enable = false)
    public CommonResult<TrialOAuthGateway.Credential> exchange(@Valid @RequestBody TrialApplicationReqVO body,
                                                               HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        Object value = request.getAttribute(TrialRequestAdvice.IDENTITY_ATTRIBUTE);
        if (!request.isSecure() || !(value instanceof TrialIdentity identity)) { throw TrialException.unauthorized(); }
        return CommonResult.success(authorizations.exchange(body.getApplicationId(), identity));
    }
}
