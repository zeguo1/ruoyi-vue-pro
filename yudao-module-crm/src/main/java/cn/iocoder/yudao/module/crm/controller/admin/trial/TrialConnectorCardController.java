package cn.iocoder.yudao.module.crm.controller.admin.trial;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.crm.controller.admin.trial.vo.TrialApplicationReqVO;
import cn.iocoder.yudao.module.crm.service.trial.*;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

/** Never publish these methods as Agent tools; only the authenticated safe-card backend may invoke them. */
@Hidden @RestController @RequiredArgsConstructor
@RequestMapping(value = "/crm/trial-connector-private", consumes = "application/json", produces = "application/json")
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
public class TrialConnectorCardController {
    private final TrialSmsVerificationService verification;
    private final TrialConnectorConsent consent;
    public record Verified(boolean verified, String expiresAt) { }

    @PostMapping("/sms/send") @PermitAll @TenantIgnore @ApiAccessLog(enable = false) @TrialConnectorCapability("SMS_VERIFICATION")
    public CommonResult<TrialSmsVerificationService.SendResult> send(@Valid @RequestBody TrialSmsVerificationController.SendRequest body, HttpServletRequest request) {
        return CommonResult.success(verification.send(TrialConnectorController.identity(request), body.getMobile()));
    }
    @PostMapping("/sms/verify") @PermitAll @TenantIgnore @ApiAccessLog(enable = false) @TrialConnectorCapability("SMS_VERIFICATION")
    public CommonResult<Verified> verify(@Valid @RequestBody TrialSmsVerificationController.VerifyRequest body, HttpServletRequest request) {
        var verified = verification.verify(TrialConnectorController.identity(request), body.getChallengeId(), body.getCode());
        return CommonResult.success(new Verified(true, verified.expiresAt())); // Proof stays in MGS; never hand it to the model.
    }
    @PostMapping("/confirm") @PermitAll @TenantIgnore @ApiAccessLog(enable = false) @TrialConnectorCapability("CONSENT")
    public CommonResult<Boolean> confirm(@Valid @RequestBody TrialApplicationReqVO body, HttpServletRequest request) {
        consent.record(body.getApplicationId(), TrialConnectorController.identity(request));
        return CommonResult.success(true);
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public CommonResult<?> invalidBody(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return CommonResult.error(1_020_100_016, "安全卡片参数无效，请检查输入");
    }
}
