package cn.iocoder.yudao.module.crm.controller.admin.trial;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.crm.service.trial.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

/** Private safe-card traffic, never an Agent tool or publicly callable SMS endpoint. */
@Hidden @RestController @RequiredArgsConstructor
@RequestMapping("/crm/trial-verification")
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
public class TrialSmsVerificationController {
    private final TrialSmsVerificationService verification;
    @Data public static class SendRequest {
        @NotBlank @Pattern(regexp = "1[3-9][0-9]{9}") private String mobile;
        @JsonAnySetter public void reject(String name, Object value) { throw new IllegalArgumentException("不允许的验证字段"); }
        @Override public String toString() { return "SendRequest[REDACTED]"; }
    }
    @Data public static class VerifyRequest {
        @NotBlank @Pattern(regexp = "[a-f0-9-]{36}") private String challengeId;
        @NotBlank @Pattern(regexp = "[0-9]{6}") private String code;
        @JsonAnySetter public void reject(String name, Object value) { throw new IllegalArgumentException("不允许的验证字段"); }
        @Override public String toString() { return "VerifyRequest[REDACTED]"; }
    }
    @PostMapping("/send") @PermitAll @TenantIgnore @TrialCapability("SMS_VERIFICATION") @ApiAccessLog(enable = false)
    public CommonResult<TrialSmsVerificationService.SendResult> send(@Valid @RequestBody SendRequest body, HttpServletRequest request) {
        return CommonResult.success(verification.send(identity(request), body.getMobile()));
    }
    @PostMapping("/verify") @PermitAll @TenantIgnore @TrialCapability("SMS_VERIFICATION") @ApiAccessLog(enable = false)
    public CommonResult<TrialSmsVerificationService.VerificationResult> verify(@Valid @RequestBody VerifyRequest body, HttpServletRequest request) {
        return CommonResult.success(verification.verify(identity(request), body.getChallengeId(), body.getCode()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public CommonResult<?> invalidBody(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        // The generic validation logger includes rejected values, which can contain the submitted SMS code.
        return CommonResult.error(1_020_100_016, "短信验证参数无效，请检查安全卡片输入");
    }
    private TrialIdentity identity(HttpServletRequest request) {
        Object value = request.getAttribute(TrialRequestAdvice.IDENTITY_ATTRIBUTE);
        if (!(value instanceof TrialIdentity identity)) { throw TrialException.unauthorized(); }
        return identity;
    }
}
