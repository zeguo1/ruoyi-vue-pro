package cn.iocoder.yudao.module.crm.controller.admin.trial.vo;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE, description = "本人试用申请引用；仅知道编号不能查询或开户")
public class TrialApplicationReqVO {
    @NotBlank @Pattern(regexp = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    @Schema(description = "登记本人试用申请接口返回的申请编号", example = "00000000-0000-0000-0000-000000000001")
    private String applicationId;
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("请求包含不允许的字段"); }
}
