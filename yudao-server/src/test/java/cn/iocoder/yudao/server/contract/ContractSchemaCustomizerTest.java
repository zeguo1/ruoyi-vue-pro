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
    static class Responses {
        public cn.iocoder.yudao.framework.common.pojo.CommonResult<Boolean> json() { return null; }
        public org.springframework.core.io.Resource download() { return null; }
        public void ids(@org.springframework.web.bind.annotation.RequestParam("ids") List<Long> ids) {}
        public void id(@org.springframework.web.bind.annotation.RequestParam Long id) {}
        public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream() { return null; }
    }
    @Test void jsonMediaIsPreciseWithoutRewritingDownloadsStreamsOrExplicitMedia() throws Exception {
        for (String name : List.of("json", "download", "stream")) {
            var operation = new io.swagger.v3.oas.models.Operation().responses(new io.swagger.v3.oas.models.responses.ApiResponses()
                    .addApiResponse("200", new io.swagger.v3.oas.models.responses.ApiResponse().content(
                            new Content().addMediaType("*/*", new MediaType().schema(new ObjectSchema())))));
            new ContractSchemaCustomizer().customize(operation, new org.springframework.web.method.HandlerMethod(new Responses(), Responses.class.getMethod(name)));
            assertTrue(operation.getResponses().get("200").getContent().containsKey(name.equals("json") ? "application/json" : "*/*"));
        }
        var explicit = new io.swagger.v3.oas.models.Operation().responses(new io.swagger.v3.oas.models.responses.ApiResponses()
                .addApiResponse("200", new io.swagger.v3.oas.models.responses.ApiResponse().content(
                        new Content().addMediaType("text/event-stream", new MediaType()))));
        new ContractSchemaCustomizer().customize(explicit, new org.springframework.web.method.HandlerMethod(new Responses(), Responses.class.getMethod("json")));
        assertTrue(explicit.getResponses().get("200").getContent().containsKey("text/event-stream"));
    }
    @Test void responseExamplesKeepTheInferredGenericResultSchema() throws Exception {
        String ref = "#/components/schemas/cn.iocoder.yudao.framework.common.pojo.CommonResultJava.lang.Boolean";
        var json = new MediaType().schema(new StringSchema()).addExamples("failure",
                new io.swagger.v3.oas.models.examples.Example().value(Map.of("code", 400, "msg", "invalid")));
        var operation = new Operation().responses(new io.swagger.v3.oas.models.responses.ApiResponses()
                .addApiResponse("200", new io.swagger.v3.oas.models.responses.ApiResponse().content(new Content()
                        .addMediaType("application/json", json).addMediaType("*/*", new MediaType().schema(new Schema<>().$ref(ref))))));
        new ContractSchemaCustomizer().customize(operation, new org.springframework.web.method.HandlerMethod(new Responses(), Responses.class.getMethod("json")));
        assertEquals(ref, json.getSchema().get$ref());
        assertTrue(json.getExamples().containsKey("failure"));
        assertEquals(Set.of("application/json"), operation.getResponses().get("200").getContent().keySet());
    }
    @Test void restoresArrayQueryTypeFromActualMethodEvenWhenFlatParameterSchemaIsString() throws Exception {
        var op = new Operation().addParametersItem(new io.swagger.v3.oas.models.parameters.QueryParameter()
                .name("ids").schema(new StringSchema()).description("已有记录编号列表"));
        new ContractSchemaCustomizer().customize(op, new org.springframework.web.method.HandlerMethod(new Responses(), Responses.class.getMethod("ids", List.class)));
        var schema = op.getParameters().get(0).getSchema();
        assertEquals("array", schema.getType());
        assertEquals("integer", schema.getItems().getType());
        assertTrue(op.getParameters().get(0).getRequired());
        var implicit = new Operation().addParametersItem(new io.swagger.v3.oas.models.parameters.QueryParameter()
                .name("id").schema(new StringSchema()._default("")));
        new ContractSchemaCustomizer().customize(implicit, new org.springframework.web.method.HandlerMethod(new Responses(), Responses.class.getMethod("id", Long.class)));
        assertEquals("integer", implicit.getParameters().get(0).getSchema().getType());
        assertNull(implicit.getParameters().get(0).getSchema().getDefault());
    }
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
