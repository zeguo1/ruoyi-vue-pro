package cn.iocoder.yudao.module.mes.controller.admin.md.workstation.vo.tool;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - MES 工装夹具资源新增/修改 Request VO")
@Data
public class MesMdWorkstationToolSaveReqVO {

    @Schema(description = "编号；创建时可省略，不要编造编号；修改时必须提交已有记录编号", example = "1024", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED)
    @jakarta.validation.constraints.NotNull(message = "修改记录编号不能为空", groups = cn.iocoder.yudao.framework.common.validation.Update.class)
    private Long id;

    @Schema(description = "工作站编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "工作站不能为空")
    private Long workstationId;

    @Schema(description = "工具类型编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "工具类型不能为空")
    private Long toolTypeId;

    @Schema(description = "数量", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "数量不能为空")
    private Integer quantity;

    @Schema(description = "备注")
    private String remark;

}
