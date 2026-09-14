package cn.iocoder.yudao.module.erp.controller.admin.sale.vo.order;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "管理后台 - ERP 销售订单修改 Request VO")
public class ErpSaleOrderUpdateReqVO extends ErpSaleOrderSaveReqVO {

    @Override
    @NotNull(message = "订单编号不能为空")
    @Positive(message = "订单编号必须大于 0")
    @Schema(description = "待修改的订单编号", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED, example = "17386")
    public Long getId() {
        return super.getId();
    }

}
