package cn.iocoder.yudao.module.crm.controller.admin.trial;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.crm.controller.admin.trial.vo.TrialFollowUpReqVO;
import cn.iocoder.yudao.module.crm.service.trial.TrialBusinessService;
import cn.iocoder.yudao.module.crm.framework.trial.TrialBusinessAccess;
import cn.iocoder.yudao.module.system.api.dict.DictDataApi;
import io.swagger.v3.oas.annotations.Operation;
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
    public record CustomerView(Long id, String name, String description) { }
    public record FollowUpView(Long id, String content, Integer type, java.time.LocalDateTime nextTime) { }
    public record FollowUpType(String value, String label) { }

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
