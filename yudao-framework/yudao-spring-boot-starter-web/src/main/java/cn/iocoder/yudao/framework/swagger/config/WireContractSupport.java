package cn.iocoder.yudao.framework.swagger.config;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.media.*;
import org.springframework.core.ResolvableType;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.HandlerMethod;
import java.lang.annotation.Annotation;
import java.time.*;
import java.util.*;

/** Documents the configured Jackson/MVC wire contract, without changing controller invocation. */
final class WireContractSupport {
    static final String TIME = "x-java-time-type";
    static final String PATTERN = "x-json-format-pattern";
    private WireContractSupport() {}

    static Schema<?> property(Schema<?> schema, AnnotatedType annotated) {
        Class<?> type = raw(annotated.getType());
        if (type == null) return schema;
        Schema<?> target = schema;
        if (type.isArray()) { type = type.getComponentType(); target = schema.getItems(); }
        else if (Collection.class.isAssignableFrom(type)) {
            type = annotated.getType() instanceof com.fasterxml.jackson.databind.JavaType j
                    ? j.getContentType().getRawClass() : ResolvableType.forType(annotated.getType()).getGeneric(0).resolve();
            target = schema.getItems();
        }
        if (target == null || type == null) return schema;
        if (type == Long.class || type == long.class) target.addExtension("x-java-long", true);
        if (type == LocalDateTime.class || type == LocalDate.class || type == LocalTime.class) {
            target.addExtension(TIME, type.getSimpleName());
            for (Annotation a : Optional.ofNullable(annotated.getCtxAnnotations()).orElse(new Annotation[0])) {
                if (a instanceof JsonFormat f && !f.pattern().isBlank()) target.addExtension(PATTERN, f.pattern());
                if (a instanceof DateTimeFormat f) {
                    String queryFormat = !f.pattern().isBlank() ? f.pattern() : switch (f.iso()) {
                        case DATE -> "yyyy-MM-dd（ISO 日期）";
                        case TIME -> "ISO 8601 时间";
                        case DATE_TIME -> "ISO 8601 日期时间";
                        default -> "";
                    };
                    if (!queryFormat.isBlank()) target.addExtension("x-query-format-pattern", queryFormat);
                }
            }
        }
        return schema;
    }
    private static Class<?> raw(java.lang.reflect.Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof com.fasterxml.jackson.databind.JavaType j) return j.getRawClass();
        return type == null ? null : ResolvableType.forType(type).resolve();
    }

    static void operation(Operation op, HandlerMethod handler) {
        op.addExtension("x-java-handler", handler.getBeanType().getName() + "#" + handler.getMethod().getName());
        op.addExtension("x-java-handler-signature", handler.getMethod().toGenericString());
        String returned = handler.getMethod().getGenericReturnType().getTypeName();
        op.addExtension("x-java-return-type", returned);
        if (handler.getMethod().getReturnType() == CommonResult.class) {
            op.addExtension("x-business-success", Map.of("path", "code", "equals", 0, "valueType", "integer", "errorPath", "msg",
                    "meaning", "本次接口处理成功；不保证 data 非空，不表示异步业务最终完成"));
        } else if (handler.getMethod().getReturnType().getName().equals("com.anji.captcha.model.common.ResponseModel")) {
            op.addExtension("x-business-success", Map.of("path", "repCode", "equals", "0000", "valueType", "string", "errorPath", "repMsg",
                    "meaning", "本次验证码操作成功，不代表业务登录或授权完成"));
        }
        var inputReview = new ArrayList<String>();
        for (var argument : handler.getMethodParameters()) {
            if (Map.class.isAssignableFrom(argument.getParameterType()) &&
                    (argument.hasParameterAnnotation(RequestParam.class) || argument.hasParameterAnnotation(RequestHeader.class)))
                inputReview.add("动态请求参数/请求头 Map：具体键由业务或渠道协议决定，不能把 Java 参数名当作 HTTP 字段名");
            if (argument.getParameterType() == String.class && argument.hasParameterAnnotation(RequestBody.class))
                inputReview.add("String @RequestBody 接收原始请求体文本；须核实该接口的 JSON/XML/文本协议，不能自动包装成 JSON 字符串");
        }
        if (!inputReview.isEmpty()) op.addExtension("x-input-manual-review", inputReview);
        if (op.getParameters() == null) op.setParameters(new ArrayList<>());
        for (var parameter : handler.getMethodParameters()) {
            RequestParam binding = parameter.getParameterAnnotation(RequestParam.class);
            if (binding == null) continue;
            String name = !binding.name().isEmpty() ? binding.name() : !binding.value().isEmpty() ? binding.value() : parameter.getParameter().getName();
            op.getParameters().stream().filter(p -> Objects.equals(name, p.getName()) && "query".equals(p.getIn())).forEach(p -> {
                p.setRequired(binding.required() && ValueConstants.DEFAULT_NONE.equals(binding.defaultValue()));
                if (p.getSchema() != null) {
                    // @Parameter(description=...) can erase the inferred type under flat-param-object.
                    var declared = parameterSchema(parameter.getGenericParameterType());
                    if (declared != null && declared.get$ref() == null && !"object".equals(declared.getType())) {
                        p.setSchema(withMetadata(declared, p.getSchema()));
                    }
                    if (ValueConstants.DEFAULT_NONE.equals(binding.defaultValue())) {
                        p.getSchema().setDefault(null); p.getSchema().setDefaultSetFlag(false);
                    } else {
                        try {
                            Object value = org.springframework.core.convert.support.DefaultConversionService.getSharedInstance()
                                    .convert(binding.defaultValue(), parameter.getParameterType());
                            ((Schema)p.getSchema()).setDefault(value);
                        } catch (RuntimeException ignored) {
                            p.getSchema().setDefault(null); p.getSchema().setDefaultSetFlag(false);
                        }
                    }
                    p.setSchema(new ContractSchemaCustomizer().customize(p.getSchema(), new AnnotatedType(parameter.getGenericParameterType())
                            .ctxAnnotations(parameter.getParameterAnnotations())));
                }
            });
        }
        for (var argument : handler.getMethodParameters()) {
            var binding = argument.getParameterAnnotation(PathVariable.class);
            if (binding == null) continue;
            String name = !binding.name().isEmpty() ? binding.name() : !binding.value().isEmpty() ? binding.value() : argument.getParameter().getName();
            op.getParameters().stream().filter(p -> "path".equals(p.getIn()) && name.equals(p.getName())).forEach(p -> {
                var declared = parameterSchema(argument.getGenericParameterType());
                if (declared != null && p.getSchema() != null) {
                    p.setSchema(withMetadata(declared, p.getSchema()));
                }
                p.setRequired(true);
            });
        }
        // Flattened MVC model parameters bypass springdoc's property customizer. Read the real field metadata.
        for (var param : op.getParameters()) {
            if (!"query".equals(param.getIn()) || param.getSchema() == null) continue;
            for (var argument : handler.getMethodParameters()) {
                if (argument.hasParameterAnnotation(RequestBody.class) || argument.hasParameterAnnotation(RequestParam.class)) continue;
                var field = org.springframework.util.ReflectionUtils.findField(argument.getParameterType(), param.getName());
                if (field != null) {
                    param.setSchema(new ContractSchemaCustomizer().customize(param.getSchema(),
                            new AnnotatedType(field.getGenericType()).ctxAnnotations(field.getAnnotations())));
                    if (field.isAnnotationPresent(DateTimeFormat.class)) {
                        param.setExample(null);
                        param.getSchema().setExample(null); param.getSchema().setExampleSetFlag(false);
                    }
                }
            }
        }
        // default-flat-param-object can otherwise turn MultipartFile into a string query parameter.
        if (Arrays.stream(handler.getMethodParameters()).anyMatch(p -> org.springframework.web.multipart.MultipartFile.class.isAssignableFrom(p.getParameterType()))) {
            var body = new ObjectSchema();
            for (var argument : handler.getMethodParameters()) {
                var binding = argument.getParameterAnnotation(RequestParam.class);
                if (binding == null) continue;
                String name = !binding.name().isEmpty() ? binding.name() : !binding.value().isEmpty() ? binding.value() : argument.getParameter().getName();
                var prior = op.getParameters().stream().filter(p -> name.equals(p.getName()) && "query".equals(p.getIn())).findFirst().orElse(null);
                Schema field = org.springframework.web.multipart.MultipartFile.class.isAssignableFrom(argument.getParameterType())
                        ? new StringSchema().format("binary")
                        : parameterSchema(argument.getGenericParameterType());
                if (field == null) continue;
                if (prior != null) {
                    field.setDescription(prior.getDescription());
                    if (prior.getSchema() != null && prior.getSchema().getDefault() != null) field.setDefault(prior.getSchema().getDefault());
                }
                body.addProperty(name, field);
                if (binding.required() && ValueConstants.DEFAULT_NONE.equals(binding.defaultValue())) body.addRequiredItem(name);
                op.getParameters().removeIf(p -> name.equals(p.getName()) && "query".equals(p.getIn()));
            }
            op.setRequestBody(new io.swagger.v3.oas.models.parameters.RequestBody().required(true)
                    .content(new Content().addMediaType("multipart/form-data", new MediaType().schema(body))));
        }
        if (op.getRequestBody() != null && op.getRequestBody().getContent() != null
                && op.getRequestBody().getContent().containsKey("multipart/form-data")) {
            var media = op.getRequestBody().getContent().get("multipart/form-data");
            Schema<?> body = media.getSchema();
            if (body != null && body.get$ref() == null) {
                op.getParameters().removeIf(p -> {
                    if (!"query".equals(p.getIn())) return false;
                    Schema<?> field = p.getSchema();
                    if (field == null) return false;
                    if (p.getDescription() != null) field.setDescription(p.getDescription());
                    body.addProperty(p.getName(), field);
                    if (Boolean.TRUE.equals(p.getRequired())) body.addRequiredItem(p.getName());
                    return true;
                });
            }
        }
        if (handler.getBeanType().getSimpleName().equals("OAuth2OpenController")) {
            String method = handler.getMethod().getName();
            if (Set.of("postAccessToken", "checkToken", "revokeToken").contains(method)) {
                for (String name : List.of("client_id", "client_secret")) {
                    op.addParametersItem(new io.swagger.v3.oas.models.parameters.QueryParameter().name(name).required(false)
                            .description("客户端凭据；未提供 HTTP Basic 时必须提供 " + name)
                            .schema(new StringSchema().writeOnly(name.equals("client_secret"))));
                }
                if (!method.equals("revokeToken")) {
                    var form = new ObjectSchema();
                    op.getParameters().removeIf(p -> {
                        if (!"query".equals(p.getIn())) return false;
                        Schema field = p.getSchema() == null ? new StringSchema() : p.getSchema();
                        if (p.getDescription() != null) field.setDescription(p.getDescription());
                        form.addProperty(p.getName(), field);
                        if (Boolean.TRUE.equals(p.getRequired())) form.addRequiredItem(p.getName());
                        return true;
                    });
                    op.setRequestBody(new io.swagger.v3.oas.models.parameters.RequestBody().required(true)
                            .description("推荐表单编码；服务端亦兼容 query 参数。按 grant_type 提供对应字段；客户端凭据可由 HTTP Basic 提供。")
                            .content(new Content().addMediaType("application/x-www-form-urlencoded", new MediaType().schema(form))));
                }
            }
            if (method.equals("approveOrDeny")) {
                op.addExtension("x-business-success-unresolved", "code=0 仅代表处理完成，data 可能为空或含 access_denied，不能认定同意授权");
            }
        }
    }

    static void document(OpenAPI api) {
        if (api.getComponents() == null || api.getComponents().getSchemas() == null || api.getPaths() == null) return;
        Map<String, Schema> components = api.getComponents().getSchemas();
        Set<String> inputs = new HashSet<>(), outputs = new HashSet<>();
        api.getPaths().values().forEach(p -> p.readOperations().forEach(op -> {
            if (op.getRequestBody() != null && op.getRequestBody().getContent() != null)
                op.getRequestBody().getContent().values().forEach(m -> collect(m.getSchema(), components, inputs));
            if (op.getResponses() != null) op.getResponses().values().forEach(r -> {
                if (r.getContent() != null) r.getContent().values().forEach(m -> collect(m.getSchema(), components, outputs));
            });
            if (op.getExtensions() != null && Objects.toString(op.getExtensions().get("x-java-handler"), "")
                    .matches(".*OAuth2OpenController#(postAccessToken|checkToken|revokeToken)")) {
                if (op.getParameters() != null) op.getParameters().stream().filter(header -> "header".equals(header.getIn()) && "Authorization".equals(header.getName()))
                        .forEach(header -> header.setDescription("HTTP Basic 客户端认证（client_id:client_secret 的 Base64）；也可使用客户端凭据参数"));
            }
            if (op.getParameters() != null) op.getParameters().forEach(param -> {
                if (param.getSchema() != null) param.setSchema(time(param.getSchema(), "query"));
            });
        }));
        components.forEach((name, schema) -> {
            String direction = inputs.contains(name) ? (outputs.contains(name) ? "both" : "input") : outputs.contains(name) ? "output" : "query";
            transform(schema, direction, Collections.newSetFromMap(new IdentityHashMap<>()));
            if (name.startsWith(CommonResult.class.getName()) && schema.getProperties() != null) {
                Schema data = (Schema) schema.getProperties().get("data");
                if (data != null && (data.getAnyOf() == null || data.getAnyOf().stream().noneMatch(s -> "null".equals(((Schema)s).getType())))) {
                    schema.addProperty("data", new ComposedSchema().description(data.getDescription())
                            .addAnyOfItem(data).addAnyOfItem(nullSchema()));
                }
                if (schema.getRequired() == null || !schema.getRequired().contains("code")) schema.addRequiredItem("code");
            }
        });
    }
    private static Schema<?> parameterSchema(java.lang.reflect.Type type) {
        var resolved = ResolvableType.forType(type);
        Class<?> clazz = resolved.resolve();
        if (clazz == null) return null;
        if (clazz.isArray()) return new ArraySchema().items(parameterSchema(clazz.getComponentType()));
        if (Collection.class.isAssignableFrom(clazz)) {
            var element = resolved.getGeneric(0);
            return new ArraySchema().items(parameterSchema(element.getType()));
        }
        if (clazz == Long.class || clazz == long.class) return new IntegerSchema().format("int64");
        if (clazz == Integer.class || clazz == int.class || clazz == Short.class || clazz == short.class || clazz == Byte.class || clazz == byte.class)
            return new IntegerSchema().format("int32");
        if (clazz == Boolean.class || clazz == boolean.class) return new BooleanSchema();
        if (clazz == String.class || clazz == char.class || clazz == Character.class
                || clazz == LocalDateTime.class || clazz == LocalDate.class || clazz == LocalTime.class) return new StringSchema();
        if (Number.class.isAssignableFrom(clazz) || clazz == double.class || clazz == float.class) return new NumberSchema();
        return io.swagger.v3.core.converter.ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(type)).schema;
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema<?> withMetadata(Schema declared, Schema existing) {
        declared.setDescription(existing.getDescription());
        declared.setEnum(existing.getEnum());
        declared.setExample(existing.getExample()); declared.setExampleSetFlag(existing.getExample() != null);
        declared.setMinimum(existing.getMinimum()); declared.setMaximum(existing.getMaximum());
        declared.setExclusiveMinimumValue(existing.getExclusiveMinimumValue());
        declared.setExclusiveMaximumValue(existing.getExclusiveMaximumValue());
        declared.setMinItems(existing.getMinItems()); declared.setMaxItems(existing.getMaxItems());
        declared.setMinLength(existing.getMinLength()); declared.setMaxLength(existing.getMaxLength());
        declared.setPattern(existing.getPattern());
        return declared;
    }
    private static Schema<?> nullSchema() {
        var schema = new Schema<>(); schema.setTypes(Set.of("null")); return schema;
    }
    @SuppressWarnings("unchecked")
    private static void collect(Schema s, Map<String,Schema> components, Set<String> names) {
        if (s == null) return;
        if (s.get$ref() != null) {
            String name = s.get$ref().substring(s.get$ref().lastIndexOf('/') + 1);
            if (names.add(name)) collect(components.get(name), components, names);
        }
        if (s.getProperties() != null) ((Map<String,Schema>)s.getProperties()).values().forEach(p -> collect(p,components,names));
        collect(s.getItems(),components,names);
        for (Object list : Arrays.asList(s.getAllOf(),s.getAnyOf(),s.getOneOf()))
            if (list != null) ((List<Schema>) list).forEach(p -> collect(p,components,names));
    }
    @SuppressWarnings("unchecked")
    private static void transform(Schema s, String direction, Set<Schema> seen) {
        if (s == null || !seen.add(s)) return;
        if (s.getProperties() != null) ((Map<String,Schema>)s.getProperties()).replaceAll((key,value) -> {
            Schema result = time(value,direction); transform(result,direction,seen); return result;
        });
        if (s.getItems() != null) { s.setItems(time(s.getItems(), direction)); transform(s.getItems(),direction,seen); }
        if (s.getAdditionalProperties() instanceof Schema additional) {
            s.setAdditionalProperties(time(additional, direction)); transform((Schema)s.getAdditionalProperties(), direction, seen);
        }
        for (Object list : Arrays.asList(s.getAllOf(), s.getAnyOf(), s.getOneOf()))
            if (list != null) for (Schema child : (List<Schema>)list) transform(child, direction, seen);
    }
    private static Schema<?> time(Schema<?> original, String direction) {
        if (original.getItems() != null) original.setItems(time(original.getItems(),direction));
        var ext = original.getExtensions();
        if (ext == null) return original;
        if (Boolean.TRUE.equals(ext.remove("x-java-long")) && (direction.equals("output") || direction.equals("both"))) {
            var integerOrString = new ComposedSchema().description(Objects.toString(original.getDescription(), "")
                    + "；Long 在 (-9007199254740991,9007199254740991) 内输出 JSON 整数，边界及以外输出十进制字符串以免精度丢失")
                    .readOnly(original.getReadOnly()).writeOnly(original.getWriteOnly())
                    .addOneOfItem(original).addOneOfItem(new StringSchema().pattern("^-?[0-9]+$").description("Long 十进制字符串，仍须在 int64 范围内"));
            integerOrString.addExtension("x-wire-java-type", "Long");
            return integerOrString;
        }
        if (!ext.containsKey(TIME)) return original;
        String type = (String) ext.get(TIME), pattern = Objects.toString(ext.get(PATTERN), "");
        Schema<?> result;
        String explanation;
        if (direction.equals("query")) {
            result = new StringSchema();
            String queryPattern = Objects.toString(ext.get("x-query-format-pattern"), "");
            explanation = "查询/表单字符串" + (queryPattern.isBlank() ? "；日期格式由 MVC 绑定规则确定" : "，格式 " + queryPattern);
        } else if (type.equals("LocalDateTime")) {
            var epoch = new IntegerSchema().format("int64").description("Unix 毫秒时间戳；按服务端时区转换成本地时间");
            var text = new StringSchema().description(pattern.isBlank()
                    ? "ISO 8601 日期时间，可含时区偏移；无偏移时按服务端本地时间；兼容整数字符串时间戳"
                    : "日期字符串格式 " + pattern + "；兼容整数字符串时间戳");
            result = direction.equals("output") ? (pattern.isBlank() ? epoch : text)
                    : new ComposedSchema().addOneOfItem(epoch).addOneOfItem(text);
            explanation = "JSON 入参支持整数毫秒时间戳或" + (pattern.isBlank() ? " ISO 8601 日期时间字符串" : " " + pattern + " 字符串")
                    + "；JSON 返回" + (pattern.isBlank() ? "为整数毫秒时间戳" : "按 " + pattern + " 格式输出") + "；不合法的值会被拒绝";
        } else {
            // Boot WRITE_DATES_AS_TIMESTAMPS=true; @JsonFormat can override string formatting.
            var text = new StringSchema().description(pattern.isBlank() ? (type.equals("LocalDate") ? "yyyy-MM-dd 日期字符串" : "HH:mm:ss[.SSS] 时间字符串") : "格式 " + pattern);
            var array = new ArraySchema().items(new IntegerSchema()).description(type.equals("LocalDate") ? "[年,月,日]" : direction.equals("output") ? "[时,分,秒?,毫秒?]" : "入参 [时,分,秒?,纳秒?]；返回第四项为毫秒（应用关闭 WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS）");
            result = direction.equals("output") ? (pattern.isBlank() ? array : text) : new ComposedSchema().addOneOfItem(text).addOneOfItem(array);
            explanation = "JSON 入参允许日期/时间字符串或数值数组；返回" + (pattern.isBlank() ? "数值数组（应用开启 WRITE_DATES_AS_TIMESTAMPS）" : "格式为 " + pattern + " 的字符串");
        }
        result.setDescription(Objects.toString(original.getDescription(), "") + "；" + explanation);
        result.setReadOnly(original.getReadOnly()); result.setWriteOnly(original.getWriteOnly());
        // Keep the concrete input/output convention as machine-readable evidence.
        result.addExtension("x-wire-java-type",type);
        result.addExtension("x-wire-direction",direction);
        if (!pattern.isBlank()) result.addExtension(PATTERN,pattern);
        return result;
    }
}
