package cn.iocoder.yudao.module.ai.controller.admin.workflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - AI 工作流新增/修改 Request VO")
@Data
public class AiWorkflowSaveReqVO {

    @Schema(description = "编号；创建时可省略，不要编造编号；修改时必须提交已有记录编号", example = "1", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED)
    @jakarta.validation.constraints.NotNull(message = "修改记录编号不能为空", groups = cn.iocoder.yudao.framework.common.validation.Update.class)
    private Long id;

    @Schema(description = "工作流标识", requiredMode = Schema.RequiredMode.REQUIRED, example = "FLOW")
    @NotEmpty(message = "工作流标识不能为空")
    private String code;

    @Schema(description = "工作流名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "工作流")
    @NotEmpty(message = "工作流名称不能为空")
    private String name;

    @Schema(description = "备注", example = "FLOW")
    private String remark;

    @Schema(description = "工作流模型", requiredMode = Schema.RequiredMode.REQUIRED, example = "{}")
    @NotEmpty(message = "工作流模型不能为空")
    private String graph;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "FLOW")
    @NotNull(message = "状态不能为空")
    private Integer status;

}
