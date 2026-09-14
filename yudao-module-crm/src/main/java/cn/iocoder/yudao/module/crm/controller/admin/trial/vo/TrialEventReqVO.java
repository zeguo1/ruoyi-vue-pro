package cn.iocoder.yudao.module.crm.controller.admin.trial.vo;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE, description = "知办可信服务事件。绑定成功与业务完成是不同事件；不接受按钮点击/发送消息作为成功证据")
public class TrialEventReqVO {
    @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.:-]{16,128}")
    private String eventId;
    @NotBlank @Pattern(regexp = "[a-f0-9-]{36}")
    private String applicationId;
    @NotBlank @Pattern(regexp = "BOUND|FIRST_BUSINESS_COMPLETED")
    private String type;
    @NotBlank @Size(max = 128)
    @Schema(description = "已完成知办成员步骤返回的成员编号，必须与申请一致")
    private String knowdoMemberId;
    @Size(max = 32)
    @Schema(description = "首次业务完成时提供本次 MGS 跟进编号；MGS 核验实际持久化结果，不只信任事件名称")
    private String businessRecordId;
    @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("事件包含不允许的字段"); }
}
