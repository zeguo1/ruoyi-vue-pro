package cn.iocoder.yudao.module.erp.service.sale;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.erp.controller.admin.sale.ErpSaleOrderController;
import cn.iocoder.yudao.module.erp.controller.admin.sale.vo.order.ErpSaleOrderSaveReqVO;
import cn.iocoder.yudao.module.erp.dal.dataobject.product.ErpProductDO;
import cn.iocoder.yudao.module.erp.dal.dataobject.sale.ErpSaleOrderDO;
import cn.iocoder.yudao.module.erp.dal.mysql.sale.ErpSaleOrderMapper;
import cn.iocoder.yudao.module.erp.dal.mysql.sale.ErpSaleOrderItemMapper;
import cn.iocoder.yudao.module.erp.dal.redis.no.ErpNoRedisDAO;
import cn.iocoder.yudao.module.erp.service.finance.ErpAccountService;
import cn.iocoder.yudao.module.erp.service.product.ErpProductService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Actual MVC validation + transactional service + real MyBatis/H2 writes; no external database/Redis. */
@Import({ErpSaleOrderServiceImpl.class, MethodValidationPostProcessor.class})
class ErpSaleOrderContractTest extends BaseDbUnitTest {
    @Resource private ErpSaleOrderService service;
    @Resource private ErpSaleOrderMapper orders;
    @Resource private ErpSaleOrderItemMapper items;
    @Resource private javax.sql.DataSource dataSource;
    @MockBean private ErpProductService products;
    @MockBean private ErpCustomerService customers;
    @MockBean private ErpAccountService accounts;
    @MockBean private AdminUserApi users;
    @MockBean private ErpNoRedisDAO numbers;
    private MockMvc mvc;
    private java.util.Map<Long, ErpProductDO> productFixtures;
    private final ObjectMapper json = JsonUtils.getObjectMapper();

    @BeforeEach
    void setup() {
        ErpSaleOrderController controller = new ErpSaleOrderController();
        ReflectionTestUtils.setField(controller, "saleOrderService", service);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(controller).setValidator(validator)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .setControllerAdvice(new GlobalExceptionHandler("erp-contract-test", mock(ApiErrorLogCommonApi.class)))
                .build();
        productFixtures = new java.util.HashMap<>();
        productFixtures.put(101L, new ErpProductDO().setId(101L).setUnitId(7L).setStatus(0));
        when(products.getProductList(anyCollection())).thenAnswer(invocation -> {
            java.util.Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().map(productFixtures::get).filter(java.util.Objects::nonNull).toList();
        });
        when(numbers.generate(anyString())).thenReturn("TEST-SO-001");
    }

    private ObjectNode valid() throws Exception {
        return (ObjectNode) json.readTree("""
                {"customerId":201,"orderTime":"2026-09-14T10:00:00",
                 "items":[{"productId":101,"count":2.5,"productPrice":100,"taxPercent":13}]}
                """);
    }

    private JsonNode call(ObjectNode body, boolean update) throws Exception {
        MockHttpServletRequestBuilder request = update ? put("/erp/sale-order/update") : post("/erp/sale-order/create");
        return json.readTree(mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andReturn().getResponse().getContentAsString());
    }

    private void emptyDatabase() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM erp_sale_order", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM erp_sale_order_items", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingItems", "emptyItems", "nullItem", "idSort", "missingProduct", "missingCount",
            "missingPrice", "zeroCount", "negativeCount", "zeroPrice", "negativePrice", "negativeProduct",
            "negativeTax", "highTax", "negativeDiscount", "highDiscount", "negativeDeposit"})
    void invalidRequestRejectedBeforeBusinessAndNoWrites(String scenario) throws Exception {
        for (boolean update : List.of(false, true)) {
            ObjectNode body = valid();
            if (update) body.put("id", 999);
            ObjectNode item = (ObjectNode) body.withArray("items").get(0);
            String field = "items";
            switch (scenario) {
                case "missingItems" -> body.remove("items");
                case "emptyItems" -> body.putArray("items");
                case "nullItem" -> body.putArray("items").addNull();
                case "idSort" -> body.putArray("items").addObject().put("id", 1).put("sort", 1);
                case "missingProduct" -> { item.remove("productId"); field = "items[0].productId"; }
                case "missingCount" -> { item.remove("count"); field = "items[0].count"; }
                case "missingPrice" -> { item.remove("productPrice"); field = "items[0].productPrice"; }
                case "zeroCount" -> item.put("count", 0);
                case "negativeCount" -> item.put("count", -1);
                case "zeroPrice" -> item.put("productPrice", 0);
                case "negativePrice" -> item.put("productPrice", -1);
                case "negativeProduct" -> item.put("productId", -1);
                case "negativeTax" -> item.put("taxPercent", -1);
                case "highTax" -> item.put("taxPercent", 101);
                case "negativeDiscount" -> { body.put("discountPercent", -1); field = "discountPercent"; }
                case "highDiscount" -> { body.put("discountPercent", 101); field = "discountPercent"; }
                case "negativeDeposit" -> { body.put("depositPrice", -1); field = "depositPrice"; }
            }
            JsonNode response = call(body, update);
            if (scenario.equals("idSort") && !update) {
                java.nio.file.Files.writeString(java.nio.file.Path.of("target/invalid-order-response.json"), response.toPrettyString());
            }
            assertEquals(400, response.path("code").asInt(), response.toString());
            assertTrue(response.path("msg").asText().contains(field), response.toString());
            verifyNoInteractions(products, customers, accounts, users, numbers);
            emptyDatabase();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "disabled", "unitMissing"})
    void invalidProductReportsIndexAndDoesNotWrite(String scenario) throws Exception {
        ObjectNode body = valid();
        ObjectNode item = body.withArray("items").addObject();
        item.put("productId", 102).put("count", 1).put("productPrice", 20);
        if (!scenario.equals("missing")) {
            productFixtures.put(102L, new ErpProductDO().setId(102L)
                    .setStatus(scenario.equals("disabled") ? 1 : 0)
                    .setUnitId(scenario.equals("unitMissing") ? null : 7L));
        }
        JsonNode response = call(body, false);
        assertEquals(scenario.equals("unitMissing") ? 1020201012 : 1020201011, response.path("code").asInt(), response.toString());
        assertTrue(response.path("msg").asText().contains("items[1]."), response.toString());
        verifyNoInteractions(numbers);
        emptyDatabase();
    }

    @Test
    void validCreateAndUpdateUseProductUnitAndConfirmedAmounts() throws Exception {
        ObjectNode body = valid();
        body.put("id", 9001); // caller-supplied create IDs must not choose primary keys
        ((ObjectNode) body.withArray("items").get(0)).put("id", 9002).put("productUnitId", 999);
        JsonNode response = call(body, false);
        assertEquals(0, response.path("code").asInt(), response.toString());
        long id = response.path("data").asLong();
        assertNotEquals(9001, id);
        ErpSaleOrderDO order = orders.selectById(id);
        assertEquals(0, new BigDecimal("282.50").compareTo(order.getTotalPrice()));
        var saved = items.selectListByOrderId(id);
        assertEquals(1, saved.size());
        assertNotEquals(9002, saved.get(0).getId());
        assertEquals(7L, saved.get(0).getProductUnitId());
        body.put("id", id).put("discountPercent", 10);
        ((ObjectNode) body.withArray("items").get(0)).put("id", saved.get(0).getId()).put("count", 3);
        response = call(body, true);
        assertEquals(0, response.path("code").asInt(), response.toString());
        assertEquals(0, new BigDecimal("305.10").compareTo(orders.selectById(id).getTotalPrice()));
        assertEquals(1, items.selectListByOrderId(id).size());
        // Invalid edit must preserve both the existing order and its detail.
        ((ObjectNode) body.withArray("items").get(0)).put("productId", 999999);
        response = call(body, true);
        assertEquals(1020201011, response.path("code").asInt(), response.toString());
        assertEquals(0, new BigDecimal("305.10").compareTo(orders.selectById(id).getTotalPrice()));
        assertEquals(101L, items.selectListByOrderId(id).get(0).getProductId());
    }

    @Test
    void omittedUnitTaxAndDiscountAreValid() throws Exception {
        ObjectNode body = valid();
        ((ObjectNode) body.withArray("items").get(0)).remove("taxPercent");
        JsonNode response = call(body, false);
        assertEquals(0, response.path("code").asInt(), response.toString());
        assertEquals(0, new BigDecimal("250").compareTo(orders.selectById(response.path("data").asLong()).getTotalPrice()));
    }

    @Test
    void updateRequiresOrderId() throws Exception {
        JsonNode response = call(valid(), true);
        assertEquals(400, response.path("code").asInt(), response.toString());
        assertTrue(response.path("msg").asText().contains("id"));
        emptyDatabase();
    }

    @Test
    void serviceProxyAlsoValidatesNestedItems() {
        ErpSaleOrderSaveReqVO request = new ErpSaleOrderSaveReqVO();
        request.setCustomerId(201L); request.setOrderTime(java.time.LocalDateTime.now());
        request.setItems(List.of(new ErpSaleOrderSaveReqVO.Item()));
        assertThrows(ConstraintViolationException.class, () -> service.createSaleOrder(request));
        verifyNoInteractions(products, numbers);
        emptyDatabase();
    }
}
