package cn.iocoder.yudao.module.fms.controller.admin.config;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.fms.controller.admin.config.vo.vouchertemplate.FmsVoucherTemplateRespVO;
import cn.iocoder.yudao.module.fms.controller.admin.config.vo.vouchertemplate.FmsVoucherTemplateSaveReqVO;
import cn.iocoder.yudao.module.fms.dal.dataobject.config.FmsVoucherTemplateCategoryDO;
import cn.iocoder.yudao.module.fms.dal.dataobject.config.FmsVoucherTemplateDO;
import cn.iocoder.yudao.module.fms.service.config.FmsAccountSetService;
import cn.iocoder.yudao.module.fms.service.config.FmsVoucherTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.common.util.collection.MapUtils.findAndThen;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "管理后台 - FMS 凭证模板")
@RestController
@RequestMapping("/fms/config/voucher-template")
@Validated
public class FmsVoucherTemplateController {

    @Resource
    private FmsVoucherTemplateService voucherTemplateService;
    @Resource
    private FmsAccountSetService accountSetService;

    @PostMapping("/create")
    @Operation(summary = "创建凭证模板")
    @PreAuthorize("@ss.hasPermission('fms:config:voucher-template:create')")
    public CommonResult<Long> createVoucherTemplate(
            @Valid @RequestBody FmsVoucherTemplateSaveReqVO createReqVO) {
        return success(voucherTemplateService.createVoucherTemplate(createReqVO, getLoginUserId()));
    }

    @PutMapping("/update")
    @Operation(summary = "修改凭证模板")
    @PreAuthorize("@ss.hasPermission('fms:config:voucher-template:update')")
    public CommonResult<Boolean> updateVoucherTemplate(
            @Validated(cn.iocoder.yudao.framework.common.validation.Update.class) @RequestBody FmsVoucherTemplateSaveReqVO updateReqVO) {
        voucherTemplateService.updateVoucherTemplate(updateReqVO, getLoginUserId());
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除凭证模板")
    @PreAuthorize("@ss.hasPermission('fms:config:voucher-template:delete')")
    public CommonResult<Boolean> deleteVoucherTemplate(@io.swagger.v3.oas.annotations.Parameter(description = "账套编号；使用当前用户有权访问的已有账套") @RequestParam("accountSetId") @NotNull Long accountSetId,
                                                       @io.swagger.v3.oas.annotations.Parameter(description = "已有凭证模板编号；从对应查询接口获取，不要编造") @RequestParam("id") @NotNull Long id) {
        voucherTemplateService.deleteVoucherTemplate(accountSetId, id, getLoginUserId());
        return success(true);
    }

    @GetMapping("/list")
    @Operation(summary = "获得凭证模板列表")
    @PreAuthorize("@ss.hasPermission('fms:config:voucher-template:query')")
    public CommonResult<List<FmsVoucherTemplateRespVO>> getVoucherTemplateList(
            @io.swagger.v3.oas.annotations.Parameter(description = "账套编号；使用当前用户有权访问的已有账套") @RequestParam("accountSetId") @NotNull Long accountSetId) {
        accountSetService.validateAccountSetReadPermission(accountSetId, getLoginUserId());
        return success(buildVoucherTemplateRespVOList(accountSetId));
    }

    @GetMapping("/simple-list")
    @Operation(summary = "获得凭证模板精简列表", description = "主要用于录凭证时套用凭证模板")
    public CommonResult<List<FmsVoucherTemplateRespVO>> getVoucherTemplateSimpleList(
            @io.swagger.v3.oas.annotations.Parameter(description = "账套编号；使用当前用户有权访问的已有账套") @RequestParam("accountSetId") @NotNull Long accountSetId) {
        accountSetService.validateAccountSetReadPermission(accountSetId, getLoginUserId());
        return success(buildVoucherTemplateRespVOList(accountSetId));
    }

    // ==================== 拼接 VO ====================

    private List<FmsVoucherTemplateRespVO> buildVoucherTemplateRespVOList(Long accountSetId) {
        Map<Long, FmsVoucherTemplateCategoryDO> categoryMap =
                voucherTemplateService.getTemplateCategoryMap(accountSetId);
        List<FmsVoucherTemplateDO> templates = voucherTemplateService.getVoucherTemplateList(accountSetId);
        return BeanUtils.toBean(templates, FmsVoucherTemplateRespVO.class, template -> {
            findAndThen(categoryMap, template.getCategoryId(),
                    category -> template.setCategoryName(category.getName()));
        });
    }

}
