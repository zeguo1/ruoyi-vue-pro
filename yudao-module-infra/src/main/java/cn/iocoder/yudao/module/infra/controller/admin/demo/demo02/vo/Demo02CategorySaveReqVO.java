package cn.iocoder.yudao.module.infra.controller.admin.demo.demo02.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 示例分类新增/修改 Request VO")
@Data
public class Demo02CategorySaveReqVO {

    @Schema(description = "编号；新增可省略，修改时必须提交已有记录编号", requiredMode = Schema.RequiredMode.NOT_REQUIRED, example = "10304")
    @jakarta.validation.constraints.NotNull(groups = cn.iocoder.yudao.framework.common.validation.Update.class, message = "修改时编号不能为空")
    private Long id;

    @Schema(description = "名字", requiredMode = Schema.RequiredMode.REQUIRED, example = "芋艿")
    @NotEmpty(message = "名字不能为空")
    private String name;

    @Schema(description = "父级编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "6080")
    @NotNull(message = "父级编号不能为空")
    private Long parentId;

}