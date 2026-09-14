package cn.iocoder.yudao.module.mes.controller.admin.wm.outsourceissue.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - MES 外协发料单新增/修改 Request VO")
@Data
public class MesWmOutsourceIssueSaveReqVO {

    @Schema(description = "发料单ID；创建时可省略，不要编造编号；修改时必须提交已有记录编号", example = "1024", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED)
    @jakarta.validation.constraints.NotNull(message = "修改记录编号不能为空", groups = cn.iocoder.yudao.framework.common.validation.Update.class)
    private Long id;

    @Schema(description = "发料单编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "WOS202603020001")
    @NotEmpty(message = "发料单编号不能为空")
    private String code;

    @Schema(description = "发料单名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "外协发料单001")
    @NotEmpty(message = "发料单名称不能为空")
    private String name;

    @Schema(description = "供应商ID", example = "1")
    private Long vendorId;

    @Schema(description = "生产工单ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "生产工单ID不能为空")
    private Long workOrderId;

    @Schema(description = "发料日期")
    private LocalDateTime issueDate;

    @Schema(description = "备注", example = "备注")
    private String remark;

}
