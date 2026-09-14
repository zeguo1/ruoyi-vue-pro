package cn.iocoder.yudao.module.crm.controller.admin.business.vo.status;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "管理后台 - 商机状态组新增/修改 Request VO")
@Data
public class CrmBusinessStatusSaveReqVO {

    @Schema(description = "主键；创建时可省略，不要编造编号；修改时必须提交已有记录编号", requiredMode = Schema.RequiredMode.NOT_REQUIRED, example = "2934")
    @jakarta.validation.constraints.NotNull(groups = cn.iocoder.yudao.framework.common.validation.Update.class, message = "修改时编号不能为空")
    private Long id;

    @Schema(description = "状态类型名", requiredMode = Schema.RequiredMode.REQUIRED, example = "李四")
    @NotEmpty(message = "状态类型名不能为空")
    private String name;

    @Schema(description = "使用的部门编号")
    private List<Long> deptIds;

    @Schema(description = "商机状态集合", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "商机状态集合不能为空")
    @Valid
    private List<Status> statuses;

    @Data
    public static class Status {

        @Schema(description = "状态编号", example = "23899")
        private Long id;

        @Schema(description = "状态名", requiredMode = Schema.RequiredMode.REQUIRED, example = "王五")
        @NotEmpty(message = "状态名不能为空")
        private String name;

        @Schema(description = "赢单率", requiredMode = Schema.RequiredMode.REQUIRED, example = "50")
        @NotNull(message = "赢单率不能为空")
        private BigDecimal percent;

        @Schema(description = "排序", hidden = true, example = "1")
        private Integer sort;

    }

}
