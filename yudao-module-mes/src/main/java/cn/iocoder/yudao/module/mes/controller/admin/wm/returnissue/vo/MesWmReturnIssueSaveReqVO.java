package cn.iocoder.yudao.module.mes.controller.admin.wm.returnissue.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - MES 生产退料单新增/修改 Request VO")
@Data
public class MesWmReturnIssueSaveReqVO {

    @Schema(description = "编号；创建时可省略，不要编造编号；修改时必须提交已有记录编号", example = "1024", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED)
    @jakarta.validation.constraints.NotNull(message = "修改记录编号不能为空", groups = cn.iocoder.yudao.framework.common.validation.Update.class)
    private Long id;

    @Schema(description = "退料单编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "RI20250226001")
    @NotBlank(message = "退料单编号不能为空")
    private String code;

    @Schema(description = "退料单名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "生产退料")
    @NotBlank(message = "退料单名称不能为空")
    private String name;

    @Schema(description = "生产工单 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "生产工单 ID 不能为空")
    private Long workOrderId;

    @Schema(description = "工作站 ID", example = "1")
    private Long workstationId;

    @Schema(description = "退料类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "退料类型不能为空")
    private Integer type;

    @Schema(description = "退料日期")
    private LocalDateTime returnDate;

    @Schema(description = "备注", example = "备注")
    private String remark;

}
