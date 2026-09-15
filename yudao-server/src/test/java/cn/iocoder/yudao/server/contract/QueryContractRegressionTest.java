package cn.iocoder.yudao.server.contract;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.erp.controller.admin.stock.ErpStockController;
import cn.iocoder.yudao.module.erp.controller.admin.statistics.ErpSaleStatisticsController;
import cn.iocoder.yudao.module.erp.controller.admin.statistics.ErpPurchaseStatisticsController;
import cn.iocoder.yudao.module.erp.service.stock.ErpStockService;
import cn.iocoder.yudao.module.erp.service.statistics.ErpSaleStatisticsService;
import cn.iocoder.yudao.module.erp.service.statistics.ErpPurchaseStatisticsService;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC binding/validation/exception handling, mock services: never accesses business storage. */
class QueryContractRegressionTest {
    private MockMvc mvc(Object controller) {
        var validation = new MethodValidationPostProcessor();
        validation.setProxyTargetClass(true);
        validation.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(validation.postProcessAfterInitialization(controller, "controller"))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(WireContractTest.mapper()))
                .setControllerAdvice(new GlobalExceptionHandler("isolated", mock(ApiErrorLogCommonApi.class))).build();
    }

    @Test void stockRequiresOneCompleteLocatorAndPreservesIdPrecedence() throws Exception {
        var controller = new ErpStockController();
        var service = mock(ErpStockService.class);
        ReflectionTestUtils.setField(controller, "stockService", service);
        var mvc = mvc(controller);
        for (var request : List.of(get("/erp/stock/get"),
                get("/erp/stock/get").param("productId", "10"),
                get("/erp/stock/get").param("warehouseId", "20"),
                get("/erp/stock/get").param("id", "0"),
                get("/erp/stock/get").param("productId", "10").param("warehouseId", "-1"))) {
            mvc.perform(request).andExpect(jsonPath("code").value(400))
                    .andExpect(jsonPath("msg").isNotEmpty());
        }
        verifyNoInteractions(service);
        mvc.perform(get("/erp/stock/get").param("id", "1").param("productId", "-1"))
                .andExpect(jsonPath("code").value(0)).andExpect(jsonPath("data").doesNotExist());
        verify(service).getStock(1L);
        mvc.perform(get("/erp/stock/get").param("productId", "10").param("warehouseId", "20"))
                .andExpect(jsonPath("code").value(0));
        verify(service).getStock(10L, 20L);
        verifyNoMoreInteractions(service);
    }

    @Test void bothMonthlyStatisticsEnforceBoundsBeforeAggregationAndUseDefault() throws Exception {
        var sale = new ErpSaleStatisticsController();
        var purchase = new ErpPurchaseStatisticsController();
        var saleService = mock(ErpSaleStatisticsService.class);
        var purchaseService = mock(ErpPurchaseStatisticsService.class);
        ReflectionTestUtils.setField(sale, "saleStatisticsService", saleService);
        ReflectionTestUtils.setField(purchase, "purchaseStatisticsService", purchaseService);
        for (var controller : List.of(sale, purchase)) {
            var mvc = mvc(controller);
            String path = controller == sale ? "/erp/sale-statistics/time-summary" : "/erp/purchase-statistics/time-summary";
            Object service = controller == sale ? saleService : purchaseService;
            for (String count : List.of("0", "-1", "121", "2147483647", "not-a-number")) {
                mvc.perform(get(path).param("count", count)).andExpect(jsonPath("code").value(400));
            }
            verifyNoInteractions(service);
            mvc.perform(get(path)).andExpect(jsonPath("code").value(0)).andExpect(jsonPath("data.length()").value(6));
            if (controller == sale) verify(saleService, times(6)).getSalePrice(any(), any());
            else verify(purchaseService, times(6)).getPurchasePrice(any(), any());
            clearInvocations(service);
            for (String count : List.of("1", "2", "120")) {
                mvc.perform(get(path).param("count", count)).andExpect(jsonPath("code").value(0))
                        .andExpect(jsonPath("data.length()").value(Integer.parseInt(count)));
            }
            if (controller == sale) verify(saleService, times(123)).getSalePrice(any(), any());
            else verify(purchaseService, times(123)).getPurchasePrice(any(), any());
            verifyNoMoreInteractions(service);
        }
    }
}
