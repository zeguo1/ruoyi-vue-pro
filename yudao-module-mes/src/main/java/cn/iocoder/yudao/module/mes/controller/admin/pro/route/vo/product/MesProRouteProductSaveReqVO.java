package cn.iocoder.yudao.module.mes.controller.admin.pro.route.vo.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "管理后台 - MES 工艺路线产品新增/修改 Request VO")
@Data
public class MesProRouteProductSaveReqVO {

    @Schema(description = "编号；创建时可省略，不要编造编号；修改时必须提交已有记录编号", example = "1", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED)
    @jakarta.validation.constraints.NotNull(message = "修改记录编号不能为空", groups = cn.iocoder.yudao.framework.common.validation.Update.class)
    private Long id;

    @Schema(description = "工艺路线编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "工艺路线编号不能为空")
    private Long routeId;

    @Schema(description = "产品物料编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "产品物料编号不能为空")
    private Long itemId;

    @Schema(description = "生产数量", example = "100")
    private Integer quantity;

    @Schema(description = "生产用时", example = "60.00")
    private BigDecimal productionTime;

    @Schema(description = "时间单位", example = "MINUTE")
    private String timeUnitType;

    @Schema(description = "备注")
    private String remark;

}
