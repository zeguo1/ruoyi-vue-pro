package cn.iocoder.yudao.module.infra.controller.admin.db.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 数据源配置创建/修改 Request VO")
@Data
public class DataSourceConfigSaveReqVO {

    @Schema(description = "主键编号；创建时可省略，不要编造编号；修改时必须提交已有记录编号", example = "1024", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED)
    @jakarta.validation.constraints.NotNull(message = "修改记录编号不能为空", groups = cn.iocoder.yudao.framework.common.validation.Update.class)
    private Long id;

    @Schema(description = "数据源名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "test")
    @NotNull(message = "数据源名称不能为空")
    private String name;

    @Schema(description = "数据源连接", requiredMode = Schema.RequiredMode.REQUIRED, example = "jdbc:mysql://127.0.0.1:3306/ruoyi-vue-pro")
    @NotNull(message = "数据源连接不能为空")
    private String url;

    @Schema(description = "用户名", requiredMode = Schema.RequiredMode.REQUIRED, example = "root")
    @NotNull(message = "用户名不能为空")
    private String username;

    @Schema(description = "密码", requiredMode = Schema.RequiredMode.REQUIRED, example = "123456")
    @NotNull(message = "密码不能为空")
    private String password;

}
