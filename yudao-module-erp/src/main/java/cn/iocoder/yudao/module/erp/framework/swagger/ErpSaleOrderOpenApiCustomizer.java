package cn.iocoder.yudao.module.erp.framework.swagger;

import cn.iocoder.yudao.module.erp.controller.admin.sale.vo.order.ErpSaleOrderSaveReqVO;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** Preserve the sales-order @DecimalMin(exclusive) bounds in OpenAPI 3.1's numeric form. */
@Component
public class ErpSaleOrderOpenApiCustomizer implements GlobalOpenApiCustomizer {

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        Schema<?> item = openApi.getComponents().getSchemas().get(ErpSaleOrderSaveReqVO.Item.class.getCanonicalName());
        if (item == null || item.getProperties() == null) {
            return; // This document group does not expose sales orders.
        }
        for (String field : List.of("count", "productPrice")) {
            Schema<?> property = (Schema<?>) item.getProperties().get(field);
            // swagger-core's Bean Validation conversion sets the 3.0 boolean, which 3.1 serialization drops.
            // Set both representations: 3.0 serializes minimum + exclusiveMinimum=true; 3.1 uses numeric 0.
            property.setMinimum(BigDecimal.ZERO);
            property.setExclusiveMinimum(true);
            property.setExclusiveMinimumValue(BigDecimal.ZERO);
        }
    }

}
