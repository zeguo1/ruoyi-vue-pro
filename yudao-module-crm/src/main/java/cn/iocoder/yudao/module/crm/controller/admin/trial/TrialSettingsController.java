package cn.iocoder.yudao.module.crm.controller.admin.trial;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.crm.service.trial.settings.*;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

/** Administrative control plane, deliberately excluded from all Agent OpenAPI catalogs. */
@Hidden @RestController @RequiredArgsConstructor
@RequestMapping("/crm/trial-settings")
@ConditionalOnProperty(prefix="mgs.trial",name="storage-enabled",havingValue="true")
public class TrialSettingsController {
    private final TrialSettingsService service;
    public record Issue(@NotNull @Min(0) Long revision, @NotBlank @Pattern(regexp="TOOLS|CARD|PRIVATE_HMAC") String kind, @Min(1) @Max(365) int days) { }
    public record Revoke(@NotNull @Min(0) Long revision, @NotBlank @Size(max=64) String keyId, @NotBlank @Pattern(regexp="TOOLS|CARD|PRIVATE_HMAC") String kind) { }
    @GetMapping("/get") @PreAuthorize("@ss.hasPermission('crm:trial-settings:query')") @ApiAccessLog(enable=false)
    public CommonResult<TrialSettingsService.View> get(HttpServletResponse response) {
        noStore(response);return CommonResult.success(service.get());
    }
    @PutMapping("/save") @PreAuthorize("@ss.hasPermission('crm:trial-settings:update')") @ApiAccessLog(enable=false)
    public CommonResult<TrialSettingsService.View> save(@Valid @RequestBody TrialSettingsInput input,HttpServletResponse response) {
        noStore(response);return CommonResult.success(service.save(input));
    }
    @PostMapping("/keys/issue") @PreAuthorize("@ss.hasPermission('crm:trial-settings:secret')") @ApiAccessLog(enable=false)
    public CommonResult<TrialSettingsService.Issued> issue(@Valid @RequestBody Issue input,HttpServletResponse response) {
        noStore(response);return CommonResult.success(service.issue(input.revision(),input.kind(),input.days()));
    }
    @PostMapping("/keys/revoke") @PreAuthorize("@ss.hasPermission('crm:trial-settings:secret')") @ApiAccessLog(enable=false)
    public CommonResult<TrialSettingsService.View> revoke(@Valid @RequestBody Revoke input,HttpServletResponse response) {
        noStore(response);return CommonResult.success(service.revoke(input.revision(),input.keyId(),input.kind()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class})
    public CommonResult<?> invalid(HttpServletResponse response) {
        noStore(response);return CommonResult.error(1_020_100_023,"配置参数无效，请检查必填项、允许名单格式和数值范围");
    }
    // Do not pass a request containing credentials to the global persisted error logger.
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public CommonResult<?> storageUnavailable(HttpServletResponse response) {
        noStore(response);return CommonResult.error(1_020_100_020,"配置存储暂不可用，请检查数据库连接和配置表迁移");
    }
    private static void noStore(HttpServletResponse response){response.setHeader("Cache-Control","no-store");response.setHeader("Pragma","no-cache");}
}
