package cn.iocoder.yudao.module.crm.controller.admin.trial;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.crm.controller.admin.trial.vo.TrialEventReqVO;
import cn.iocoder.yudao.module.crm.service.trial.TrialEventService;
import cn.iocoder.yudao.module.crm.service.trial.TrialException;
import cn.iocoder.yudao.module.crm.service.trial.TrialIdentity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/crm/trial-event")
@Tag(name = "知办可信服务 - 试用事件")
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
@RequiredArgsConstructor
public class TrialEventController {
    private final TrialEventService events;
    @PostMapping("/accept") @PermitAll @TenantIgnore @TrialCapability("EVENTS") @ApiAccessLog(enable = false)
    @Operation(summary = "接收账号绑定或首次业务完成事实", description = "需独立 EVENTS 服务签名权限，归属校验及事件幂等。返回成功仅代表事件被接收，不表示整套开户或业务完成。")
    public CommonResult<Boolean> accept(@Valid @RequestBody TrialEventReqVO event, HttpServletRequest request) {
        Object identity = request.getAttribute(TrialRequestAdvice.IDENTITY_ATTRIBUTE);
        if (!(identity instanceof TrialIdentity trusted)) { throw TrialException.unauthorized(); }
        events.accept(trusted, event);
        return CommonResult.success(true);
    }
}
