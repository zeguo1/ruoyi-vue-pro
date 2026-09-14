package cn.iocoder.yudao.module.ai.controller.admin.model.vo.tool;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.validation.InEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

@Schema(description = "管理后台 - AI 工具新增/修改 Request VO")
@Data
public class AiToolSaveReqVO {

    @Schema(description = "工具编号；新增可省略，修改时必须提交已有记录编号", requiredMode = Schema.RequiredMode.NOT_REQUIRED, example = "19661")
    @jakarta.validation.constraints.NotNull(groups = cn.iocoder.yudao.framework.common.validation.Update.class, message = "修改时编号不能为空")
    private Long id;

    @Schema(description = "工具名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "王五")
    @NotEmpty(message = "工具名称不能为空")
    private String name;

    @Schema(description = "工具描述", example = "你猜")
    private String description;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @InEnum(CommonStatusEnum.class)
    private Integer status;

}