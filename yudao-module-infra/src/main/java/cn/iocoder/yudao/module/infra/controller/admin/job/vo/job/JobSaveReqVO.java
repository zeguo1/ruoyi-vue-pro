package cn.iocoder.yudao.module.infra.controller.admin.job.vo.job;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "管理后台 - 定时任务创建/修改 Request VO")
@Data
public class JobSaveReqVO {

    @Schema(description = "任务编号；创建时可省略，不要编造编号；修改时必须提交已有记录编号", example = "1024", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED)
    @jakarta.validation.constraints.NotNull(message = "修改记录编号不能为空", groups = cn.iocoder.yudao.framework.common.validation.Update.class)
    private Long id;

    @Schema(description = "任务名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "测试任务")
    @NotEmpty(message = "任务名称不能为空")
    private String name;

    @Schema(description = "处理器的名字", requiredMode = Schema.RequiredMode.REQUIRED, example = "sysUserSessionTimeoutJob")
    @NotEmpty(message = "处理器的名字不能为空")
    private String handlerName;

    @Schema(description = "处理器的参数", example = "yudao")
    private String handlerParam;

    @Schema(description = "CRON 表达式", requiredMode = Schema.RequiredMode.REQUIRED, example = "0/10 * * * * ? *")
    @NotEmpty(message = "CRON 表达式不能为空")
    private String cronExpression;

    @Schema(description = "重试次数", requiredMode = Schema.RequiredMode.REQUIRED, example = "3")
    @NotNull(message = "重试次数不能为空")
    private Integer retryCount;

    @Schema(description = "重试间隔", requiredMode = Schema.RequiredMode.REQUIRED, example = "1000")
    @NotNull(message = "重试间隔不能为空")
    private Integer retryInterval;

    @Schema(description = "监控超时时间", example = "1000")
    private Integer monitorTimeout;

}
