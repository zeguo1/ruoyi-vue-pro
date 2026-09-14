package cn.iocoder.yudao.module.mes.controller.admin.wm.outsourcereceipt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - MES 外协入库单新增/修改 Request VO")
@Data
public class MesWmOutsourceReceiptSaveReqVO {

    @Schema(description = "编号；创建时可省略，不要编造编号；修改时必须提交已有记录编号", example = "1024", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED)
    @jakarta.validation.constraints.NotNull(message = "修改记录编号不能为空", groups = cn.iocoder.yudao.framework.common.validation.Update.class)
    private Long id;

    @Schema(description = "入库单编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "OR2026030001")
    @NotEmpty(message = "入库单编码不能为空")
    private String code;

    @Schema(description = "入库单名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "外协加工入库单")
    @NotEmpty(message = "入库单名称不能为空")
    private String name;

    @Schema(description = "外协工单编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "外协工单不能为空")
    private Long workOrderId;

    @Schema(description = "供应商编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "供应商不能为空")
    private Long vendorId;

    @Schema(description = "入库日期", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "入库日期不能为空")
    private LocalDateTime receiptDate;

    @Schema(description = "备注", example = "备注")
    private String remark;

}
