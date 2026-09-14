package cn.iocoder.yudao.server.contract;

import cn.iocoder.yudao.module.erp.controller.admin.sale.vo.order.ErpSaleOrderSaveReqVO;
import cn.iocoder.yudao.module.erp.controller.admin.sale.vo.order.ErpSaleOrderUpdateReqVO;
import cn.iocoder.yudao.module.pms.controller.admin.pm.project.vo.group.PmsProjectGroupSortReqVO;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.core.jackson.TypeNameResolver;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiItemSchemaTest {
    private ModelConverters converters() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        assertEquals("true", yaml.getObject().getProperty("springdoc.use-fqn"));
        TypeNameResolver.std.setUseFqn(true);
        return ModelConverters.getInstance(true);
    }

    private Schema<?> schema(Class<?> type) {
        ResolvedSchema resolved = converters().resolveAsResolvedSchema(new AnnotatedType(type).resolveAsRef(true));
        return resolved.referencedSchemas.get(resolved.schema.get$ref().substring("#/components/schemas/".length()));
    }

    @Test
    void controllerItemModelsHaveDistinctNamesAndTheirOwnFields() throws Exception {
        Set<String> references = new HashSet<>();
        int count = 0;
        java.util.Map<String, Object> manifest = new java.util.TreeMap<>();
        for (var resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath*:cn/iocoder/yudao/module/**/controller/**/vo/**/*.class")) {
            if (!resource.getFilename().endsWith("$Item.class")) continue;
            String path = resource.getURL().toString();
            String name = path.substring(path.indexOf("cn/iocoder/yudao/module/"))
                    .replace('/', '.').replace(".class", "");
            Class<?> type = Class.forName(name, false, getClass().getClassLoader());
            ResolvedSchema resolved = converters().resolveAsResolvedSchema(new AnnotatedType(type).resolveAsRef(true));
            assertTrue(references.add(resolved.schema.get$ref()), "Colliding model: " + name);
            assertNotEquals("#/components/schemas/Item", resolved.schema.get$ref());
            Schema<?> item = schema(type);
            for (var field : type.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) continue;
                assertTrue(item.getProperties().containsKey(field.getName()), name + ": " + field.getName());
            }
            manifest.put(resolved.schema.get$ref(), item.getProperties().keySet());
            count++;
        }
        assertTrue(count >= 35, "Expected controller Item models across all enabled modules, got " + count);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/item-model-contracts.json"),
                io.swagger.v3.core.util.Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
        System.out.println("Distinct controller Item schemas verified: " + count);
    }

    @Test
    void orderRequestAndPmsSortModelsCannotOverwriteEachOther() {
        for (Class<?> type : new Class<?>[]{ErpSaleOrderSaveReqVO.class, ErpSaleOrderUpdateReqVO.class}) {
            ResolvedSchema resolved = converters().resolveAsResolvedSchema(new AnnotatedType(type).resolveAsRef(true));
            Schema<?> order = schema(type);
            Schema<?> array = (Schema<?>) order.getProperties().get("items");
            Schema<?> item = resolved.referencedSchemas.get(array.getItems().get$ref().substring("#/components/schemas/".length()));
            assertTrue(item.getProperties().keySet().containsAll(Set.of("productId", "productPrice", "count", "taxPercent")));
            assertTrue(item.getRequired().containsAll(Set.of("productId", "productPrice", "count")));
            assertFalse(item.getRequired().contains("productUnitId"));
            assertTrue(Boolean.TRUE.equals(((Schema<?>) item.getProperties().get("productUnitId")).getReadOnly()));
            assertFalse(item.getProperties().containsKey("sort"));
            assertTrue(order.getRequired().contains("items"));
            assertEquals(type == ErpSaleOrderUpdateReqVO.class, order.getRequired().contains("id"));
        }
        assertEquals(Set.of("id", "sort"), schema(PmsProjectGroupSortReqVO.Item.class).getProperties().keySet());
    }
}
