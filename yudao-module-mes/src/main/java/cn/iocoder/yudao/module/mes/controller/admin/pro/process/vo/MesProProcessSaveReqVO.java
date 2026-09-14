package cn.iocoder.yudao.module.mes.controller.admin.pro.process.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - MES 生产工序新增/修改 Request VO")
@Data
public class MesProProcessSaveReqVO {

    @Schema(description = "编号；创建时可省略，不要编造编号；修改时必须提交已有记录编号", example = "1", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED)
    @jakarta.validation.constraints.NotNull(message = "修改记录编号不能为空", groups = cn.iocoder.yudao.framework.common.validation.Update.class)
    private Long id;

    @Schema(description = "工序编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "PROCESS001")
    @NotBlank(message = "工序编码不能为空")
    private String code;

    @Schema(description = "工序名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "下料工序")
    @NotBlank(message = "工序名称不能为空")
    private String name;

    @Schema(description = "工艺要求", example = "按照图纸尺寸进行切割")
    private String attention;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "状态不能为空")
    private Integer status;

    @Schema(description = "备注", example = "金属板材下料")
    private String remark;

}
