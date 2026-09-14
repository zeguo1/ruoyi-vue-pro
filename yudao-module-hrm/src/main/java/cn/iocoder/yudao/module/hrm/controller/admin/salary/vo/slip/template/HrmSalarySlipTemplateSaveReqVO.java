package cn.iocoder.yudao.module.hrm.controller.admin.salary.vo.slip.template;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - HRM 工资条模板新增/修改 Request VO")
@Data
public class HrmSalarySlipTemplateSaveReqVO {

    @Schema(description = "编号；创建时可省略，不要编造编号；修改时必须提交已有记录编号", example = "1024", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED)
    @jakarta.validation.constraints.NotNull(message = "修改记录编号不能为空", groups = cn.iocoder.yudao.framework.common.validation.Update.class)
    private Long id;

    @Schema(description = "模板名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "标准工资条模板")
    @NotBlank(message = "模板名称不能为空")
    @Size(max = 64, message = "模板名称不能超过 64 个字符")
    private String name;

    @Schema(description = "是否隐藏空值项", example = "true")
    private Boolean hideEmpty;

    @Schema(description = "选项列表")
    @Valid
    private List<HrmSalarySlipTemplateOptionVO> options;

}
