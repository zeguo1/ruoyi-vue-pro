package cn.iocoder.yudao.module.system.controller.admin.captcha.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "验证码挑战校验请求；token 来自获取验证码响应，坐标来自用户交互，不得编造；captchaVerification 属于后续业务二次验证，不是此接口的入参")
@Data
@EqualsAndHashCode(callSuper = true)
public class CaptchaCheckReqVO extends CaptchaGetReqVO {
    @Schema(description = "本次验证码挑战的 token，由获取验证码接口返回", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "验证码 token 不能为空")
    private String token;

    @Schema(description = "客户端按本次挑战密钥及验证码组件协议编码的真实用户坐标；不能用任意字符串代替", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "验证码坐标 pointJson 不能为空")
    private String pointJson;
}
