package cn.iocoder.yudao.module.system.controller.admin.captcha;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.system.controller.admin.captcha.vo.CaptchaGetReqVO;
import cn.iocoder.yudao.module.system.controller.admin.captcha.vo.CaptchaCheckReqVO;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import jakarta.validation.Valid;
import org.springframework.validation.BindingResult;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.anji.captcha.model.common.ResponseModel;
import com.anji.captcha.model.vo.CaptchaVO;
import com.anji.captcha.service.CaptchaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "管理后台 - 验证码")
@RestController("adminCaptchaController")
@RequestMapping(value = "/system/captcha", produces = "application/json")
public class CaptchaController {

    @Resource
    private CaptchaService captchaService;

    @PostMapping({"/get"})
    @Operation(summary = "获得验证码")
    @PermitAll
    @TenantIgnore
    public ResponseModel get(@Valid @RequestBody CaptchaGetReqVO reqVO, BindingResult errors, HttpServletRequest request) {
        if (errors.hasErrors()) return invalidRequest(errors);
        CaptchaVO data = BeanUtils.toBean(reqVO, CaptchaVO.class);
        assert request.getRemoteHost() != null;
        data.setBrowserInfo(getRemoteId(request));
        return captchaService.get(data);
    }

    @PostMapping("/check")
    @Operation(summary = "校验验证码")
    @PermitAll
    @TenantIgnore
    public ResponseModel check(@Valid @RequestBody CaptchaCheckReqVO reqVO, BindingResult errors, HttpServletRequest request) {
        if (errors.hasErrors()) return invalidRequest(errors);
        CaptchaVO data = BeanUtils.toBean(reqVO, CaptchaVO.class);
        data.setBrowserInfo(getRemoteId(request));
        return captchaService.check(data);
    }

    private static ResponseModel invalidRequest(BindingResult errors) {
        String message = errors.getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage()).sorted()
                .collect(java.util.stream.Collectors.joining("; "));
        return ResponseModel.errorMsg(com.anji.captcha.model.common.RepCodeEnum.PARAM_FORMAT_ERROR, message);
    }

    public static String getRemoteId(HttpServletRequest request) {
        String ip = ServletUtils.getClientIP(request);
        String ua = request.getHeader("user-agent");
        if (StrUtil.isNotBlank(ip)) {
            return ip + ua;
        }
        return request.getRemoteAddr() + ua;
    }

}
