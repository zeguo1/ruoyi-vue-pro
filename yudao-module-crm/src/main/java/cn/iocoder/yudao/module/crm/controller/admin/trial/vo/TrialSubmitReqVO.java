package cn.iocoder.yudao.module.crm.controller.admin.trial.vo;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE, description = "知办可信服务提交试用申请；身份及经验证联系方式通过服务签名提供，不属于模型参数")
public class TrialSubmitReqVO {
    @NotBlank @Size(max = 100)
    @Schema(description = "团队名称，仅用于留资，不用于合并身份", example = "示例体验团队")
    private String team;
    @NotBlank @Size(max = 30)
    @Schema(description = "联系人称呼", example = "王女士")
    private String contactName;
    @NotBlank @Pattern(regexp = "CRM_FOLLOW_UP")
    @Schema(description = "首期体验场景：查询虚构客户并新增跟进", allowableValues = "CRM_FOLLOW_UP", example = "CRM_FOLLOW_UP")
    private String scenario;
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("试用申请包含不允许的字段"); }
}
