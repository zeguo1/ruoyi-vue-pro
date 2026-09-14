package cn.iocoder.yudao.server.contract;

import cn.iocoder.yudao.framework.common.validation.Update;
import cn.iocoder.yudao.module.trade.controller.app.order.vo.AppTradeOrderSettlementReqVO;
import cn.iocoder.yudao.module.promotion.controller.admin.point.vo.product.PointProductSaveReqVO;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure request validation; no business services or database can be reached. */
class FullContractValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test void allCorrectedUpdateIdsAreRequiredOnlyForUpdates() throws Exception {
        var rows = io.swagger.v3.core.util.Json.mapper().readTree(Files.readString(Path.of("../script/openapi/full-update-contracts.json")));
        assertEquals(62, rows.size());
        for (var row : rows) {
            String name = row.path("model").asText().split("/src/main/java/")[1].replace('/', '.').replace(".java", "");
            Class<?> type = Class.forName(name);
            assertTrue(validator.validateValue(type, "id", null).isEmpty(), name + " create id is optional");
            assertFalse(validator.validateValue(type, "id", null, Update.class).isEmpty(), name + " update id is required");
            assertTrue(validator.validateValue(type, "id", 101L, Update.class).isEmpty(), name);
            String controllerName = row.path("controller").asText().split("/src/main/java/")[1].replace('/', '.').replace(".java", "");
            boolean found = false;
            for (var method : Class.forName(controllerName).getDeclaredMethods()) {
                if (!method.getName().startsWith("update")) continue;
                for (var parameter : method.getParameters()) {
                    if (parameter.getType() != type) continue;
                    var group = parameter.getAnnotation(org.springframework.validation.annotation.Validated.class);
                    assertNotNull(group, controllerName);
                    assertTrue(Arrays.asList(group.value()).contains(Update.class), controllerName);
                    found = true;
                }
            }
            assertTrue(found, controllerName);
        }
    }

    @Test void everyAdditionalErpDocumentRejectsEmptyAndIncompleteItems() throws Exception {
        for (String suffix : List.of("purchase.vo.order.ErpPurchaseOrderSaveReqVO", "purchase.vo.in.ErpPurchaseInSaveReqVO",
                "purchase.vo.returns.ErpPurchaseReturnSaveReqVO", "sale.vo.out.ErpSaleOutSaveReqVO", "sale.vo.returns.ErpSaleReturnSaveReqVO")) {
            Class<?> type = Class.forName("cn.iocoder.yudao.module.erp.controller.admin." + suffix);
            Object request = type.getConstructor().newInstance();
            var list = type.getDeclaredField("items"); list.setAccessible(true);
            list.set(request, List.of());
            assertTrue(validator.validate(request).stream().anyMatch(v -> v.getPropertyPath().toString().equals("items")), suffix);
            list.set(request, Arrays.asList((Object) null));
            assertTrue(validator.validate(request).stream().anyMatch(v -> v.getPropertyPath().toString().startsWith("items[0]")), suffix);
            Class<?> itemType = Class.forName(type.getName() + "$Item");
            Object item = itemType.getConstructor().newInstance();
            list.set(request, List.of(item));
            Set<String> errors = new HashSet<>();
            validator.validate(request).forEach(v -> errors.add(v.getPropertyPath().toString()));
            assertTrue(errors.contains("items[0].productId"), suffix + errors);
            assertTrue(errors.contains("items[0].count"), suffix + errors);
            assertFalse(errors.contains("items[0].productUnitId"), suffix + errors);
            for (var field : itemType.getDeclaredFields()) {
                field.setAccessible(true);
                if (field.getType() == Long.class && !field.getName().equals("productUnitId")) field.set(item, 101L);
                if (field.getType() == BigDecimal.class) field.set(item, BigDecimal.ONE);
            }
            assertTrue(validator.validate(item).isEmpty(), suffix + validator.validate(item));
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Object parsed = mapper.readValue("{\"productId\":101,\"count\":1,\"productUnitId\":999}", itemType);
            var unit = itemType.getDeclaredField("productUnitId"); unit.setAccessible(true);
            assertNull(unit.get(parsed), "Client cannot override authoritative product unit");
        }
    }

    @Test void cartAndDirectPurchaseRemainValidAlternatives() {
        var direct = new AppTradeOrderSettlementReqVO.Item(); direct.setSkuId(101L); direct.setCount(2);
        assertTrue(validator.validate(direct).isEmpty());
        var cart = new AppTradeOrderSettlementReqVO.Item(); cart.setCartId(201L);
        assertTrue(validator.validate(cart).isEmpty());
        assertFalse(validator.validate(new AppTradeOrderSettlementReqVO.Item()).isEmpty());
        direct.setCount(0); assertFalse(validator.validate(direct).isEmpty());
        var root = new AppTradeOrderSettlementReqVO(); root.setSeckillActivityId(1L);
        assertDoesNotThrow(() -> validator.validate(root));
    }

    @Test void pointsProductDoesNotRequireParentDerivedFields() {
        var item = new PointProductSaveReqVO(); item.setSkuId(101L); item.setCount(1); item.setPoint(100);
        item.setPrice(0); item.setStock(20);
        assertTrue(validator.validate(item).isEmpty(), validator.validate(item).toString());
        item.setSkuId(null); assertFalse(validator.validate(item).isEmpty());
    }
}
