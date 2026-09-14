package cn.iocoder.yudao.module.hrm.controller.admin.recruit.vo.channel;

import com.mzt.logapi.starter.annotation.DiffLogField;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - HRM 招聘渠道新增/修改 Request VO")
@Data
public class HrmRecruitChannelSaveReqVO {

    @Schema(description = "招聘渠道编号；创建时可省略，不要编造编号；修改时必须提交已有记录编号", example = "1024", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED)
    @jakarta.validation.constraints.NotNull(message = "修改记录编号不能为空", groups = cn.iocoder.yudao.framework.common.validation.Update.class)
    private Long id;

    @Schema(description = "渠道名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "BOSS 直聘")
    @NotBlank(message = "渠道名称不能为空")
    @Size(max = 255, message = "渠道名称不能超过 255 个字符")
    @DiffLogField(name = "渠道名称")
    private String name;

    @Schema(description = "显示顺序", requiredMode = Schema.RequiredMode.REQUIRED, example = "10")
    @NotNull(message = "显示顺序不能为空")
    @Min(value = 0, message = "显示顺序不能小于 0")
    @DiffLogField(name = "显示顺序")
    private Integer sort;

    @Schema(description = "备注", example = "常用渠道")
    @Size(max = 500, message = "备注不能超过 500 个字符")
    @DiffLogField(name = "备注")
    private String remark;

}
