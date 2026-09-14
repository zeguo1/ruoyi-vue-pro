package cn.iocoder.yudao.module.fms.controller.admin.config.vo.vouchertemplatecategory;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - FMS 凭证模板分类保存 Request VO")
@Data
public class FmsVoucherTemplateCategorySaveReqVO {

    @Schema(description = "分类编号；创建时可省略，不要编造编号；修改时必须提交已有记录编号", example = "1024", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED)
    @jakarta.validation.constraints.NotNull(message = "修改记录编号不能为空", groups = cn.iocoder.yudao.framework.common.validation.Update.class)
    private Long id;

    @Schema(description = "账套编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "账套编号不能为空")
    private Long accountSetId;

    @Schema(description = "分类名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "日常收支")
    @NotBlank(message = "分类名称不能为空")
    @Size(max = 255, message = "分类名称长度不能超过 255 个字符")
    private String name;

}
