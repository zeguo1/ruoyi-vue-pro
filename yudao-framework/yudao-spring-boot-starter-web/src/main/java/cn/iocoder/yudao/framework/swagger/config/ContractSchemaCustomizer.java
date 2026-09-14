package cn.iocoder.yudao.framework.swagger.config;

import cn.iocoder.yudao.framework.common.validation.InEnum;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import jakarta.validation.constraints.*;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.web.method.HandlerMethod;

import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.util.*;

/** Keep generated contracts faithful to validation metadata, including OpenAPI 3.1 numeric bounds. */
public class ContractSchemaCustomizer implements PropertyCustomizer, GlobalOpenApiCustomizer, GlobalOperationCustomizer {

    private static final Map<String, Map<String, String>> EXTERNAL_DESCRIPTIONS = externalDescriptions();

    private static Map<String, Map<String, String>> externalDescriptions() {
        try (var input = new org.springframework.core.io.ClassPathResource("openapi/external-schema-descriptions.json").getInputStream()) {
            return io.swagger.v3.core.util.Json.mapper().readValue(input,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Map<String, String>>>() {});
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot load verified SDK contract descriptions", e);
        }
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Schema customize(Schema schema, AnnotatedType type) {
        if (schema == null) return null;
        Annotation[] annotations = type.getCtxAnnotations();
        if (annotations == null) return schema;
        for (Annotation annotation : annotations) {
            if (annotation instanceof io.swagger.v3.oas.annotations.media.Schema doc) {
                // An unspecified annotation default is not a real business default.
                if (doc.defaultValue().isEmpty() && "".equals(schema.getDefault())) clearDefault(schema);
            }
            if (annotation.annotationType().getName().equals("io.swagger.annotations.ApiModelProperty")
                    && blank(schema.getDescription())) {
                String description = legacyText(annotation, "value");
                if (!blank(description)) schema.setDescription(description);
            }
            if (!defaultGroup(annotation)) continue;
            if (annotation instanceof DecimalMin min) minimum(schema, new BigDecimal(min.value()), !min.inclusive());
            if (annotation instanceof DecimalMax max) maximum(schema, new BigDecimal(max.value()), !max.inclusive());
            if (annotation instanceof Min min) minimum(schema, BigDecimal.valueOf(min.value()), false);
            if (annotation instanceof Max max) maximum(schema, BigDecimal.valueOf(max.value()), false);
            if (annotation instanceof Positive) minimum(schema, BigDecimal.ZERO, true);
            if (annotation instanceof PositiveOrZero) minimum(schema, BigDecimal.ZERO, false);
            if (annotation instanceof Negative) maximum(schema, BigDecimal.ZERO, true);
            if (annotation instanceof NegativeOrZero) maximum(schema, BigDecimal.ZERO, false);
            if (annotation instanceof InEnum inEnum) {
                var constants = inEnum.value().getEnumConstants();
                if (constants.length > 0) {
                    Schema target = schema.getItems() == null ? schema : schema.getItems();
                    target.setEnum(Arrays.asList(constants[0].array()));
                }
            }
        }
        return schema;
    }

    @Override
    public Operation customize(Operation operation, HandlerMethod handler) {
        if (blank(operation.getSummary())) {
            for (Annotation annotation : handler.getMethod().getAnnotations()) {
                if (annotation.annotationType().getName().equals("io.swagger.annotations.ApiOperation")) {
                    String description = legacyText(annotation, "value");
                    if (!blank(description)) operation.setSummary(description);
                }
            }
            if (blank(operation.getSummary()) && !blank(operation.getDescription())) {
                operation.setSummary(operation.getDescription().split("[\\r\\n]", 2)[0]);
            }
        }
        for (var parameter : handler.getMethodParameters()) {
            var validated = parameter.getParameterAnnotation(org.springframework.validation.annotation.Validated.class);
            if (validated != null && Arrays.asList(validated.value()).contains(cn.iocoder.yudao.framework.common.validation.Update.class)
                    && operation.getRequestBody() != null && operation.getRequestBody().getContent() != null) {
                operation.getRequestBody().getContent().values().forEach(media -> {
                    // This requirement belongs only to the update operation, never to the shared create schema.
                    var update = new io.swagger.v3.oas.models.media.ObjectSchema();
                    update.addProperty("id", new io.swagger.v3.oas.models.media.IntegerSchema().format("int64")
                            .description("要修改的已有记录编号"));
                    update.addRequiredItem("id");
                    media.setSchema(new io.swagger.v3.oas.models.media.ComposedSchema()
                            .addAllOfItem(media.getSchema()).addAllOfItem(update));
                });
            }
        }
        return operation;
    }

    @Override
    public void customise(OpenAPI api) {
        Set<Schema<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (api.getComponents() != null && api.getComponents().getSchemas() != null) {
            api.getComponents().getSchemas().forEach((name, schema) -> {
                Map<String, String> external = EXTERNAL_DESCRIPTIONS.get(name);
                if (external != null && schema.getProperties() != null) {
                    if (blank(schema.getDescription())) schema.setDescription(external.get("@description"));
                    // SDK copy/status helper setters are not business fields and have no response getter.
                    for (String hidden : external.getOrDefault("@hide", "").split(",")) schema.getProperties().remove(hidden);
                    external.forEach((field, description) -> {
                        Schema property = (Schema) schema.getProperties().get(field);
                        if (property != null && blank(property.getDescription())) property.setDescription(description);
                    });
                }
                if (name.startsWith("cn.iocoder.") && schema.getProperties() != null) {
                    Schema translation = (Schema) schema.getProperties().get("transMap");
                    if (translation != null && blank(translation.getDescription()))
                        translation.setDescription("字段翻译结果映射，由后端 EasyTrans 转换生成");
                }
                normalize(schema, seen);
            });
        }
        if (api.getPaths() != null) api.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
            if (operation.getParameters() != null) operation.getParameters().forEach(p -> normalize(p.getSchema(), seen));
            if (operation.getRequestBody() != null && operation.getRequestBody().getContent() != null)
                operation.getRequestBody().getContent().values().forEach(m -> normalize(m.getSchema(), seen));
            if (operation.getResponses() != null) operation.getResponses().values().forEach(response -> {
                if (response.getContent() != null) response.getContent().values().forEach(m -> normalize(m.getSchema(), seen));
            });
        }));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void normalize(Schema schema, Set<Schema<?>> seen) {
        if (schema == null || !seen.add(schema)) return;
        if ("".equals(schema.getDefault()) && !"string".equals(schema.getType())) clearDefault(schema);
        if (schema.getDefault() != null && !validExample(schema, schema.getDefault())) clearDefault(schema);
        if (schema.getDefault() == null) schema.setDefaultSetFlag(false);
        if (schema.getExample() != null && !validExample(schema, schema.getExample())) {
            // A misleading example is worse than none. Never invent IDs, quantities or prices.
            schema.setExample(null);
            schema.setExampleSetFlag(false);
        }
        if (Boolean.TRUE.equals(schema.getExclusiveMinimum()) && schema.getMinimum() != null)
            schema.setExclusiveMinimumValue(schema.getMinimum());
        if (Boolean.TRUE.equals(schema.getExclusiveMaximum()) && schema.getMaximum() != null)
            schema.setExclusiveMaximumValue(schema.getMaximum());
        if (schema.getProperties() != null) schema.getProperties().values().forEach(p -> normalize((Schema) p, seen));
        normalize(schema.getItems(), seen);
        if (schema.getAdditionalProperties() instanceof Schema additional) normalize(additional, seen);
        for (List<Schema> list : Arrays.asList(schema.getAllOf(), schema.getAnyOf(), schema.getOneOf()))
            if (list != null) list.forEach(s -> normalize(s, seen));
    }

    private static void clearDefault(Schema<?> schema) {
        schema.setDefault(null);
        schema.setDefaultSetFlag(false);
    }

    private static boolean validExample(Schema<?> schema, Object example) {
        if (example instanceof com.fasterxml.jackson.databind.JsonNode node)
            example = io.swagger.v3.core.util.Json.mapper().convertValue(node, Object.class);
        String type = schema.getType();
        if (type == null && schema.getTypes() != null && schema.getTypes().size() == 1)
            type = schema.getTypes().iterator().next();
        if (type == null) return true;
        boolean matches = switch (type) {
            case "integer" -> example instanceof Number number && new BigDecimal(number.toString()).stripTrailingZeros().scale() <= 0;
            case "number" -> example instanceof Number;
            case "boolean" -> example instanceof Boolean;
            case "string" -> example instanceof String;
            case "array" -> example instanceof Collection<?> || example != null && example.getClass().isArray();
            case "object" -> example instanceof Map<?, ?>;
            default -> true;
        };
        if (!matches) return false;
        if (schema.getEnum() != null && !schema.getEnum().contains(example)) return false;
        if (example instanceof Number number) {
            BigDecimal value = new BigDecimal(number.toString());
            if (schema.getMinimum() != null && value.compareTo(schema.getMinimum()) < 0) return false;
            if (schema.getMaximum() != null && value.compareTo(schema.getMaximum()) > 0) return false;
            if (schema.getExclusiveMinimumValue() != null && value.compareTo(schema.getExclusiveMinimumValue()) <= 0) return false;
            if (schema.getExclusiveMaximumValue() != null && value.compareTo(schema.getExclusiveMaximumValue()) >= 0) return false;
        }
        return true;
    }

    private static void minimum(Schema<?> schema, BigDecimal value, boolean exclusive) {
        BigDecimal previous = schema.getExclusiveMinimumValue() != null ? schema.getExclusiveMinimumValue() : schema.getMinimum();
        if (previous != null && previous.compareTo(value) > 0) return;
        if (previous != null && previous.compareTo(value) == 0)
            exclusive |= Boolean.TRUE.equals(schema.getExclusiveMinimum()) || schema.getExclusiveMinimumValue() != null;
        schema.setMinimum(value);
        schema.setExclusiveMinimum(exclusive);
        schema.setExclusiveMinimumValue(exclusive ? value : null);
    }

    private static void maximum(Schema<?> schema, BigDecimal value, boolean exclusive) {
        BigDecimal previous = schema.getExclusiveMaximumValue() != null ? schema.getExclusiveMaximumValue() : schema.getMaximum();
        if (previous != null && previous.compareTo(value) < 0) return;
        if (previous != null && previous.compareTo(value) == 0)
            exclusive |= Boolean.TRUE.equals(schema.getExclusiveMaximum()) || schema.getExclusiveMaximumValue() != null;
        schema.setMaximum(value);
        schema.setExclusiveMaximum(exclusive);
        schema.setExclusiveMaximumValue(exclusive ? value : null);
    }

    private static boolean defaultGroup(Annotation annotation) {
        try {
            Class<?>[] groups = (Class<?>[]) annotation.annotationType().getMethod("groups").invoke(annotation);
            return groups.length == 0 || Arrays.asList(groups).contains(jakarta.validation.groups.Default.class);
        } catch (NoSuchMethodException ignored) {
            return true;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read validation groups", e);
        }
    }

    private static String legacyText(Annotation annotation, String field) {
        try {
            return (String) annotation.annotationType().getMethod(field).invoke(annotation);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read legacy API annotation", e);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
