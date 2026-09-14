package cn.iocoder.yudao.module.system.controller.admin.captcha.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Schema(description = "获取验证码挑战请求；图片、密钥与 token 由验证码服务返回")
@Data
public class CaptchaGetReqVO {
    @Schema(description = "验证码类型：blockPuzzle 滑动拼图、clickWord 文字点选", allowableValues = {"blockPuzzle", "clickWord"}, requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "验证码类型不能为空")
    @Pattern(regexp = "blockPuzzle|clickWord", message = "验证码类型必须为 blockPuzzle 或 clickWord")
    private String captchaType;

    @Schema(description = "客户端验证码组件实例标识，可省略；限流优先使用后端取得的客户端地址信息")
    private String clientUid;

    @Schema(description = "客户端请求时间，兼容验证码组件的可选字段")
    private Long ts;
}
