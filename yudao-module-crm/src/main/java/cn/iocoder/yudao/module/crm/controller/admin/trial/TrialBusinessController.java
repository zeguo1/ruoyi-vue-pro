package cn.iocoder.yudao.module.crm.controller.admin.trial;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.crm.controller.admin.trial.vo.TrialFollowUpReqVO;
import cn.iocoder.yudao.module.crm.service.trial.TrialBusinessService;
import cn.iocoder.yudao.module.crm.framework.trial.TrialBusinessAccess;
import cn.iocoder.yudao.module.system.api.dict.DictDataApi;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.List;

@RestController
@RequestMapping("/crm/trial-business")
@Tag(name = "本人演示业务 - 个人 MGS 授权")
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
@RequiredArgsConstructor
public class TrialBusinessController {
    private final TrialBusinessService business;
    private final TrialBusinessAccess access;
    private final DictDataApi dict;
    public record CustomerView(
            @Schema(description = "本人演示客户编号") Long id,
            @Schema(description = "虚构演示客户名称") String name,
            @Schema(description = "演示客户用途说明") String description) { }
    public record FollowUpView(
            @Schema(description = "已持久化的跟进记录编号") Long id,
            @Schema(description = "用户确认的跟进内容") String content,
            @Schema(description = "跟进类型，来源于 crm_follow_up_type 字典") Integer type,
            @Schema(description = "下次联系时间，按全局日期时间序列化约定返回") java.time.LocalDateTime nextTime) { }
    public record FollowUpType(
            @Schema(description = "跟进类型字典值，作为新增跟进的 type 取值来源") String value,
            @Schema(description = "跟进类型的显示名称") String label) { }

    @GetMapping("/customer") @PreAuthorize("@ss.hasPermission('crm:trial-business:query')")
    @Operation(summary = "查询本人虚构演示客户", description = "必须使用本人的个人 MGS 授权。没有客户编号参数，不允许读取其他试用用户或既有演示数据。")
    public CommonResult<CustomerView> customer() {
        var customer = business.customer();
        return CommonResult.success(new CustomerView(customer.getId(), customer.getName(), "用于客户查询和跟进的虚构演示数据"));
    }
    @GetMapping("/follow-ups") @PreAuthorize("@ss.hasPermission('crm:trial-business:query')")
    @Operation(summary = "查看本人演示客户的跟进结果")
    public CommonResult<List<FollowUpView>> followUps() {
        return CommonResult.success(business.followUps().stream().map(r -> new FollowUpView(r.getId(), r.getContent(), r.getType(), r.getNextTime())).toList());
    }
    @GetMapping("/follow-up-types") @PreAuthorize("@ss.hasPermission('crm:trial-business:query')")
    @Operation(summary = "查询真实可用的跟进类型")
    public CommonResult<List<FollowUpType>> types() {
        access.requireCurrent();
        return CommonResult.success(dict.getDictDataList("crm_follow_up_type").stream().map(d -> new FollowUpType(d.getValue(), d.getLabel())).toList());
    }
    @PostMapping("/follow-up") @PreAuthorize("@ss.hasPermission('crm:trial-business:follow-up')") @ApiAccessLog(requestEnable = false)
    @Operation(summary = "Agent 为本人演示客户新增跟进", description = "需要用户提供并确认内容、类型和下次联系时间。成功 code=0 且 data 为持久化跟进记录编号。重复幂等键不会重复写入。")
    public CommonResult<Long> followUp(@Valid @RequestBody TrialFollowUpReqVO request) { return CommonResult.success(business.followUp(request)); }
}
