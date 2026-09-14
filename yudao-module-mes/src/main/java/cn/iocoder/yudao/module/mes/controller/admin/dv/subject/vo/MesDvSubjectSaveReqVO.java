package cn.iocoder.yudao.module.mes.controller.admin.dv.subject.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - MES 点检保养项目新增/修改 Request VO")
@Data
public class MesDvSubjectSaveReqVO {

    @Schema(description = "编号；新增可省略，修改时必须提交已有记录编号", requiredMode = Schema.RequiredMode.NOT_REQUIRED, example = "1024")
    @jakarta.validation.constraints.NotNull(groups = cn.iocoder.yudao.framework.common.validation.Update.class, message = "修改时编号不能为空")
    private Long id;

    @Schema(description = "项目编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "CHK001")
    @NotEmpty(message = "项目编码不能为空")
    private String code;

    @Schema(description = "项目名称", example = "注塑机外观检查")
    private String name;

    @Schema(description = "项目类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "项目类型不能为空")
    private Integer type;

    @Schema(description = "项目内容", requiredMode = Schema.RequiredMode.REQUIRED, example = "检查注塑机外壳是否有裂纹")
    @NotEmpty(message = "项目内容不能为空")
    private String content;

    @Schema(description = "标准", example = "外观完好，无明显损伤")
    private String standard;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "状态不能为空")
    private Integer status;

    @Schema(description = "备注", example = "备注")
    private String remark;

    }
