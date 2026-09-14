package cn.iocoder.yudao.module.crm.controller.admin.product.vo.category;

import cn.idev.excel.annotation.ExcelProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - CRM 产品分类列表 Request VO")
@Data
public class CrmProductCategoryListReqVO {

    @ExcelProperty("名称")
    @Schema(description = "产品分类名称查询条件")
    private String name;

    @ExcelProperty("父级 id")
    @Schema(description = "父产品分类编号查询条件")
    private Long parentId;

    @ExcelProperty("创建时间")
    @Schema(hidden = true, description = "兼容保留字段；当前产品分类查询不使用此条件，不作为工具筛选输入")
    private LocalDateTime createTime;

}
