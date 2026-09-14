package cn.iocoder.yudao.module.mes.controller.admin.pro.workorder.vo.bom;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "管理后台 - MES 生产工单 BOM 新增/修改 Request VO")
@Data
public class MesProWorkOrderBomSaveReqVO {

    @Schema(description = "编号；创建时可省略，不要编造编号；修改时必须提交已有记录编号", requiredMode = Schema.RequiredMode.NOT_REQUIRED, example = "1024")
    @jakarta.validation.constraints.NotNull(groups = cn.iocoder.yudao.framework.common.validation.Update.class, message = "修改时编号不能为空")
    private Long id;

    @Schema(description = "生产工单编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "100")
    @NotNull(message = "生产工单编号不能为空")
    private Long workOrderId;

    @Schema(description = "BOM 物料编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "200")
    @NotNull(message = "BOM 物料不能为空")
    private Long itemId;

    @Schema(description = "预计使用量", requiredMode = Schema.RequiredMode.REQUIRED, example = "10.00")
    @NotNull(message = "预计使用量不能为空")
    private BigDecimal quantity;

    @Schema(description = "备注", example = "备注")
    private String remark;

}
