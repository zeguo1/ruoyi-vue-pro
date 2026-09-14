package cn.iocoder.yudao.module.erp.controller.admin.sale.vo.order;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - ERP 销售订单新增/修改 Request VO")
@Data
public class ErpSaleOrderSaveReqVO {

    @Schema(description = "订单编号；仅修改时必填，新增由后端生成，忽略传入值", example = "17386")
    private Long id;

    @Schema(description = "客户编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1724")
    @NotNull(message = "客户编号不能为空")
    @Positive(message = "客户编号必须大于 0")
    private Long customerId;

    @Schema(description = "下单时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "下单时间不能为空")
    private LocalDateTime orderTime;

    @Schema(description = "销售员编号", example = "1888")
    private Long saleUserId;

    @Schema(description = "结算账户编号", example = "31189")
    private Long accountId;

    @Schema(description = "优惠率，百分比，范围 0 到 100；省略按 0 计算", example = "99.88")
    @DecimalMin(value = "0", message = "优惠率不能小于 0")
    @DecimalMax(value = "100", message = "优惠率不能大于 100")
    private BigDecimal discountPercent;

    @Schema(description = "定金金额，单位：元", example = "7127")
    @DecimalMin(value = "0", message = "定金金额不能小于 0")
    private BigDecimal depositPrice;

    @Schema(description = "附件地址", example = "https://www.iocoder.cn")
    private String fileUrl;

    @Schema(description = "备注", example = "你猜")
    private String remark;

    @Schema(description = "订单清单列表，至少一项；数量和成交单价须由调用方确认", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "订单清单不能为空")
    @Valid
    private List<@NotNull(message = "订单明细不能为空") Item> items;

    @Data
    public static class Item {

        @Schema(description = "订单项编号", example = "11756")
        private Long id;

        @Schema(description = "产品编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "3113")
        @NotNull(message = "产品编号不能为空")
        @Positive(message = "产品编号必须大于 0")
        private Long productId;

        @Schema(description = "后端从产品资料取得单位编号；调用方无需提交，传入值忽略", accessMode = Schema.AccessMode.READ_ONLY)
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        private Long productUnitId;

        @Schema(description = "成交单价，单位：元，必须大于 0；由调用方确认，不自动补价格", requiredMode = Schema.RequiredMode.REQUIRED, example = "100.00")
        @NotNull(message = "产品单价不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "产品单价必须大于 0")
        private BigDecimal productPrice;

        @Schema(description = "产品数量", requiredMode = Schema.RequiredMode.REQUIRED, example = "100.00")
        @NotNull(message = "产品数量不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "产品数量必须大于 0")
        private BigDecimal count;

        @Schema(description = "税率，百分比，范围 0 到 100；省略不计税", example = "99.88")
        @DecimalMin(value = "0", message = "税率不能小于 0")
        @DecimalMax(value = "100", message = "税率不能大于 100")
        private BigDecimal taxPercent;

        @Schema(description = "备注", example = "随便")
        private String remark;

    }

}
