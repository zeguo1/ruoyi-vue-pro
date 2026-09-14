package cn.iocoder.yudao.module.crm.controller.admin.trial.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "本人业务体验说明，只返回已准备的真实资源引用，不包含凭据")
public record TrialGuideRespVO(
        @Schema(description = "两侧账号及演示资源是否就绪") boolean accountReady,
        @Schema(description = "本人的申请编号") String applicationId,
        @Schema(description = "当前状态下的真实下一步说明") String instructions,
        @Schema(description = "实际生成的虚构演示客户编号，未就绪时为空字符串") String customerId,
        @Schema(description = "MGS 办理结果查看地址，未就绪时为空字符串") String mgsUrl) { }
