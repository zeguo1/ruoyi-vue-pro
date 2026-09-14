package cn.iocoder.yudao.module.mp.controller.admin.account.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Schema(description = "管理后台 - 公众号账号分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class MpAccountPageReqVO extends PageParam {

    @Schema(description = "公众号名称，模糊匹配")
    private String name;

    @Schema(description = "公众号账号，模糊匹配")
    private String account;

    @Schema(description = "公众号 appid，模糊匹配")
    private String appId;

}
