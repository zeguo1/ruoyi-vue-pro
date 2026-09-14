package cn.iocoder.yudao.server.contract;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.validation.InEnum;
import cn.iocoder.yudao.framework.common.validation.Update;
import cn.iocoder.yudao.framework.swagger.config.ContractSchemaCustomizer;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.media.*;
import jakarta.validation.constraints.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContractSchemaCustomizerTest {
    static class Fields {
        @Min(5) @Positive BigDecimal amount;
        @DecimalMin(value="0", inclusive=false) BigDecimal positive;
        @Min(value=8, groups=Update.class) Integer conditional;
        @InEnum(CommonStatusEnum.class) Integer status;
    }
    private Schema<?> property(String name) throws Exception {
        var f = Fields.class.getDeclaredField(name);
        return new ContractSchemaCustomizer().customize(new NumberSchema(), new AnnotatedType(f.getGenericType()).ctxAnnotations(f.getAnnotations()));
    }
    @Test void boundsIntersectAndNonDefaultGroupsDoNotLeak() throws Exception {
        assertEquals(new BigDecimal("5"), property("amount").getMinimum());
        assertNull(property("conditional").getMinimum());
        assertEquals(List.of(0,1), property("status").getEnum());
        var serialized = io.swagger.v3.core.util.Json31.mapper().valueToTree(property("positive"));
        assertTrue(serialized.path("exclusiveMinimum").isNumber());
        assertEquals(BigDecimal.ZERO, serialized.path("exclusiveMinimum").decimalValue());
    }
    @Test void removesInvalidDefaultsAndExamplesWithoutFabricatingReplacements() {
        var wrong = new IntegerSchema(); wrong.setDefault(""); wrong.setExample("我是一个用户");
        var valid = new IntegerSchema(); valid.setDefault(1); valid.setExample(2);
        var object = new ObjectSchema(); object.setDefault(com.fasterxml.jackson.databind.node.NullNode.instance);
        var components = new Components().addSchemas("object",object).addSchemas("wrong",wrong).addSchemas("valid",valid);
        new ContractSchemaCustomizer().customise(new OpenAPI().components(components));
        var json = io.swagger.v3.core.util.Json31.mapper().valueToTree(components);
        assertFalse(json.path("schemas").path("wrong").has("default"),json.toString());
        assertFalse(json.path("schemas").path("object").has("default"),json.toString());
        assertFalse(json.path("schemas").path("wrong").has("example"),json.toString());
        assertEquals(1,json.path("schemas").path("valid").path("default").intValue());
        assertEquals(2,json.path("schemas").path("valid").path("example").intValue());
    }
}
