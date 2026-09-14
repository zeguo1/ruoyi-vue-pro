package cn.iocoder.yudao.module.system.controller.admin.permission.vo.permission;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.Set;

@Schema(description = "管理后台 - 赋予角色菜单 Request VO")
@Data
public class PermissionAssignRoleMenuReqVO {

    @Schema(description = "角色编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "角色编号不能为空")
    private Long roleId;

    @Schema(description = "菜单编号完整列表，全量替换已有授权；可省略，遗漏、null 或空数组均清空全部授权；追加需先查询并合并已有编号", nullable = true)
    private Set<@NotNull(message = "授权编号不能为空") Long> menuIds = Collections.emptySet(); // 兜底

}
