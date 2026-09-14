package cn.iocoder.yudao.module.mes.controller.admin.wm.outsourcereceipt.vo.line;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "管理后台 - MES 外协入库单行新增/修改 Request VO")
@Data
public class MesWmOutsourceReceiptLineSaveReqVO {

    @Schema(description = "编号；创建时可省略，不要编造编号；修改时必须提交已有记录编号", example = "1024", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED)
    @jakarta.validation.constraints.NotNull(message = "修改记录编号不能为空", groups = cn.iocoder.yudao.framework.common.validation.Update.class)
    private Long id;

    @Schema(description = "入库单编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "入库单编号不能为空")
    private Long receiptId;

    @Schema(description = "物料编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "物料编号不能为空")
    private Long itemId;

    @Schema(description = "入库数量", requiredMode = Schema.RequiredMode.REQUIRED, example = "500.00")
    @NotNull(message = "入库数量不能为空")
    private BigDecimal quantity;

    @Schema(description = "生产日期")
    private LocalDateTime productionDate;

    @Schema(description = "有效期")
    private LocalDateTime expireDate;

    @Schema(description = "生产批号", example = "PB20260301")
    private String lotNumber;

    @Schema(description = "是否需要质检")
    private Boolean iqcCheckFlag;

    @Schema(description = "备注", example = "备注")
    private String remark;

}
