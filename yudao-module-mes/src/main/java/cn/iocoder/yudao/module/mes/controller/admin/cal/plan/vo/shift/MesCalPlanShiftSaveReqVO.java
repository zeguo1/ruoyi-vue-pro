package cn.iocoder.yudao.module.mes.controller.admin.cal.plan.vo.shift;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - MES 计划班次新增/修改 Request VO")
@Data
public class MesCalPlanShiftSaveReqVO {

    @Schema(description = "班次编号；创建时可省略，不要编造编号；修改时必须提交已有记录编号", example = "1024", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED)
    @jakarta.validation.constraints.NotNull(message = "修改记录编号不能为空", groups = cn.iocoder.yudao.framework.common.validation.Update.class)
    private Long id;

    @Schema(description = "排班计划编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "排班计划不能为空")
    private Long planId;

    @Schema(description = "显示顺序", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "显示顺序不能为空")
    private Integer sort;

    @Schema(description = "班次名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "白班")
    @NotEmpty(message = "班次名称不能为空")
    private String name;

    @Schema(description = "开始时间", requiredMode = Schema.RequiredMode.REQUIRED, example = "08:00")
    @NotEmpty(message = "开始时间不能为空")
    private String startTime;

    @Schema(description = "结束时间", requiredMode = Schema.RequiredMode.REQUIRED, example = "17:00")
    @NotEmpty(message = "结束时间不能为空")
    private String endTime;

    @Schema(description = "备注")
    private String remark;

    }
