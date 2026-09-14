package cn.iocoder.yudao.module.crm.controller.admin.trial.vo;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE, description = "本人演示客户跟进。客户、负责人由已授权账号确定，不接受模型指定。禁止附件和跨业务关联。")
public class TrialFollowUpReqVO {
    @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.:-]{16,128}")
    @Schema(description = "本次业务操作幂等键，重复提交必须保持内容一致")
    private String idempotencyKey;
    @NotBlank @Size(max = 1000)
    @Schema(description = "用户确认的跟进内容，不得填写密码、验证码等凭据")
    private String content;
    @NotNull
    @Schema(description = "实际跟进类型，从 follow-up-types 查询选择，不要编造")
    private Integer type;
    @NotNull
    @Schema(description = "用户指定的下次联系时间；未提供时请询问用户")
    private LocalDateTime nextTime;
    @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("不允许的演示跟进字段"); }
}
