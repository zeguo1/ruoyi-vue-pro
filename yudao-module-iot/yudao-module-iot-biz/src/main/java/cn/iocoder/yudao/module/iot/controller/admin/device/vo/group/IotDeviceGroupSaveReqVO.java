package cn.iocoder.yudao.module.iot.controller.admin.device.vo.group;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - IoT 设备分组新增/修改 Request VO")
@Data
public class IotDeviceGroupSaveReqVO {

    @Schema(description = "分组 ID；新增可省略，修改时必须提交已有记录编号", requiredMode = Schema.RequiredMode.NOT_REQUIRED, example = "3583")
    @jakarta.validation.constraints.NotNull(groups = cn.iocoder.yudao.framework.common.validation.Update.class, message = "修改时编号不能为空")
    private Long id;

    @Schema(description = "分组名字", requiredMode = Schema.RequiredMode.REQUIRED, example = "李四")
    @NotEmpty(message = "分组名字不能为空")
    private String name;

    @Schema(description = "分组状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "分组状态不能为空")
    private Integer status;

    @Schema(description = "分组描述", example = "你说的对")
    private String description;

}