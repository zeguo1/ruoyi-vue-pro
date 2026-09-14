package cn.iocoder.yudao.module.erp.controller.admin.stock;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.collection.MapUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.excel.core.util.ExcelUtils;
import cn.iocoder.yudao.module.erp.controller.admin.product.vo.product.ErpProductRespVO;
import cn.iocoder.yudao.module.erp.controller.admin.stock.vo.check.ErpStockCheckPageReqVO;
import cn.iocoder.yudao.module.erp.controller.admin.stock.vo.check.ErpStockCheckRespVO;
import cn.iocoder.yudao.module.erp.controller.admin.stock.vo.check.ErpStockCheckSaveReqVO;
import cn.iocoder.yudao.module.erp.dal.dataobject.stock.ErpStockCheckDO;
import cn.iocoder.yudao.module.erp.dal.dataobject.stock.ErpStockCheckItemDO;
import cn.iocoder.yudao.module.erp.service.product.ErpProductService;
import cn.iocoder.yudao.module.erp.service.stock.ErpStockCheckService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.apilog.core.enums.OperateTypeEnum.EXPORT;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMultiMap;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertSet;

@Tag(name = "管理后台 - ERP 库存盘点单")
@RestController
@RequestMapping("/erp/stock-check")
@Validated
public class ErpStockCheckController {

    @Resource
    private ErpStockCheckService stockCheckService;
    @Resource
    private ErpProductService productService;

    @Resource
    private AdminUserApi adminUserApi;

    @PostMapping("/create")
    @Operation(summary = "创建库存盘点单")
    @PreAuthorize("@ss.hasPermission('erp:stock-check:create')")
    public CommonResult<Long> createStockCheck(@Valid @RequestBody ErpStockCheckSaveReqVO createReqVO) {
        return success(stockCheckService.createStockCheck(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新库存盘点单")
    @PreAuthorize("@ss.hasPermission('erp:stock-check:update')")
    public CommonResult<Boolean> updateStockCheck(@org.springframework.validation.annotation.Validated(cn.iocoder.yudao.framework.common.validation.Update.class) @RequestBody ErpStockCheckSaveReqVO updateReqVO) {
        stockCheckService.updateStockCheck(updateReqVO);
        return success(true);
    }

    @PutMapping("/update-status")
    @Operation(summary = "更新库存盘点单的状态")
    @PreAuthorize("@ss.hasPermission('erp:stock-check:update-status')")
    public CommonResult<Boolean> updateStockCheckStatus(@io.swagger.v3.oas.annotations.Parameter(description = "已有库存盘点单编号；从对应查询接口获取，不要编造") @RequestParam("id") Long id,
                                                     @io.swagger.v3.oas.annotations.Parameter(description = "目标审核状态：10 未审核（反审核），20 已审核；具体操作仍受单据业务状态限制") @cn.iocoder.yudao.framework.common.validation.InEnum(value = cn.iocoder.yudao.module.erp.enums.ErpAuditStatus.class, message = "审核状态只能是 10（未审核）或 20（已审核）") @RequestParam("status") Integer status) {
        stockCheckService.updateStockCheckStatus(id, status);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除库存盘点单")
    @Parameter(name = "ids", description = "编号数组", required = true)
    @PreAuthorize("@ss.hasPermission('erp:stock-check:delete')")
    public CommonResult<Boolean> deleteStockCheck(@RequestParam("ids") List<Long> ids) {
        stockCheckService.deleteStockCheck(ids);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得库存盘点单")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('erp:stock-check:query')")
    public CommonResult<ErpStockCheckRespVO> getStockCheck(@RequestParam("id") Long id) {
        ErpStockCheckDO stockCheck = stockCheckService.getStockCheck(id);
        if (stockCheck == null) {
            return success(null);
        }
        List<ErpStockCheckItemDO> stockCheckItemList = stockCheckService.getStockCheckItemListByCheckId(id);
        Map<Long, ErpProductRespVO> productMap = productService.getProductVOMap(
                convertSet(stockCheckItemList, ErpStockCheckItemDO::getProductId));
        return success(BeanUtils.toBean(stockCheck, ErpStockCheckRespVO.class, stockCheckVO ->
                stockCheckVO.setItems(BeanUtils.toBean(stockCheckItemList, ErpStockCheckRespVO.Item.class, item ->
                        MapUtils.findAndThen(productMap, item.getProductId(), product -> item.setProductName(product.getName())
                                .setProductBarCode(product.getBarCode()).setProductUnitName(product.getUnitName()))))));
    }

    @GetMapping("/page")
    @Operation(summary = "获得库存盘点单分页")
    @PreAuthorize("@ss.hasPermission('erp:stock-check:query')")
    public CommonResult<PageResult<ErpStockCheckRespVO>> getStockCheckPage(@Valid ErpStockCheckPageReqVO pageReqVO) {
        PageResult<ErpStockCheckDO> pageResult = stockCheckService.getStockCheckPage(pageReqVO);
        return success(buildStockCheckVOPageResult(pageResult));
    }

    @GetMapping("/export-excel")
    @Operation(summary = "导出库存盘点单 Excel")
    @PreAuthorize("@ss.hasPermission('erp:stock-check:export')")
    @ApiAccessLog(operateType = EXPORT)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功时返回文件字节；不适用 CommonResult 的 code 成功条件",
            content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/vnd.ms-excel",
                    schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string", format = "binary")))
    public void exportStockCheckExcel(@Valid ErpStockCheckPageReqVO pageReqVO,
              HttpServletResponse response) throws IOException {
        pageReqVO.setPageSize(PageParam.PAGE_SIZE_NONE);
        List<ErpStockCheckRespVO> list = buildStockCheckVOPageResult(stockCheckService.getStockCheckPage(pageReqVO)).getList();
        // 导出 Excel
        ExcelUtils.write(response, "库存盘点单.xls", "数据", ErpStockCheckRespVO.class, list);
    }

    private PageResult<ErpStockCheckRespVO> buildStockCheckVOPageResult(PageResult<ErpStockCheckDO> pageResult) {
        if (CollUtil.isEmpty(pageResult.getList())) {
            return PageResult.empty(pageResult.getTotal());
        }
        // 1.1 盘点项
        List<ErpStockCheckItemDO> stockCheckItemList = stockCheckService.getStockCheckItemListByCheckIds(
                convertSet(pageResult.getList(), ErpStockCheckDO::getId));
        Map<Long, List<ErpStockCheckItemDO>> stockCheckItemMap = convertMultiMap(stockCheckItemList, ErpStockCheckItemDO::getCheckId);
        // 1.2 产品信息
        Map<Long, ErpProductRespVO> productMap = productService.getProductVOMap(
                convertSet(stockCheckItemList, ErpStockCheckItemDO::getProductId));
        // 1.3 管理员信息
        Map<Long, AdminUserRespDTO> userMap = adminUserApi.getUserMap(
                convertSet(pageResult.getList(), stockCheck -> Long.parseLong(stockCheck.getCreator())));
        // 2. 开始拼接
        return BeanUtils.toBean(pageResult, ErpStockCheckRespVO.class, stockCheck -> {
            stockCheck.setItems(BeanUtils.toBean(stockCheckItemMap.get(stockCheck.getId()), ErpStockCheckRespVO.Item.class,
                    item -> MapUtils.findAndThen(productMap, item.getProductId(), product -> item.setProductName(product.getName())
                            .setProductBarCode(product.getBarCode()).setProductUnitName(product.getUnitName()))));
            stockCheck.setProductNames(CollUtil.join(stockCheck.getItems(), "，", ErpStockCheckRespVO.Item::getProductName));
            MapUtils.findAndThen(userMap, Long.parseLong(stockCheck.getCreator()), user -> stockCheck.setCreatorName(user.getNickname()));
        });
    }

}