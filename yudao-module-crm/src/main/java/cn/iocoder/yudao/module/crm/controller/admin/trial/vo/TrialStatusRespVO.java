package cn.iocoder.yudao.module.crm.controller.admin.trial.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "试用进度。code=0 仅表示本次命令/查询成功；仅 accountReady=true 表示两侧账号、授权、样例和安全交付准备完成")
public record TrialStatusRespVO(
        @Schema(description = "申请编号，不是领取凭证") String applicationId,
        @Schema(description = "申请状态", allowableValues = {"SUBMITTED", "PROVISIONING", "READY", "REVOKING", "EXPIRED"}) String status,
        @Schema(description = "整套账号就绪条件；过期时始终为 false") boolean accountReady,
        @Schema(description = "实际截止时间，ISO-8601 UTC") String expiresAt,
        @Schema(description = "各步骤非敏感进度") List<StepProgress> steps,
        @Schema(description = "下一步说明") String nextAction,
        @Schema(description = "安全卡片类型，不包含凭证") String cardType,
        @Schema(description = "非秘密交付记录引用；知办必须经原申请人的可信会话再次鉴权才能领取") String cardReference) {
    public record StepProgress(String step, String state, String errorCode) { }
}
