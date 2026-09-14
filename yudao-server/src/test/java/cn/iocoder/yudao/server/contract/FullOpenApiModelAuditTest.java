package cn.iocoder.yudao.server.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Constraint;
import jakarta.validation.Valid;
import org.junit.jupiter.api.Test;
import org.springdoc.core.providers.SpringDocJavadocProvider;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Full source-model inventory, regenerated during regression; never connects to business services. */
class FullOpenApiModelAuditTest {
    @Test
    void inventoryEveryControllerModel() throws Exception {
        var javadoc = new SpringDocJavadocProvider();
        Map<String, Object> models = new TreeMap<>();
        List<Map<String, Object>> missingCascade = new ArrayList<>();
        for (var resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath*:cn/iocoder/yudao/module/**/controller/**/vo/**/*.class")) {
            String path = resource.getURL().toString();
            String name = path.substring(path.indexOf("cn/iocoder/yudao/module/")).replace('/', '.').replace(".class", "");
            Class<?> type = Class.forName(name, false, getClass().getClassLoader());
            if (type.isAnonymousClass() || type.isSynthetic() || type.isEnum()) continue;
            Map<String, Object> fields = new TreeMap<>();
            for (Class<?> owner = type; owner != Object.class && owner != null; owner = owner.getSuperclass()) {
                for (Field field : owner.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic() || field.isAnnotationPresent(JsonIgnore.class)) continue;
                    Schema annotation = field.getAnnotation(Schema.class);
                    if (annotation != null && annotation.hidden()) continue;
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("declaringClass", owner.getName());
                    info.put("javaType", field.getGenericType().getTypeName());
                    info.put("description", annotation == null || annotation.description().isEmpty()
                            ? Objects.toString(javadoc.getFieldJavadoc(field), "") : annotation.description());
                    info.put("requiredMode", annotation == null ? "AUTO" : annotation.requiredMode().name());
                    info.put("constraints", Arrays.stream(field.getAnnotations()).filter(a -> a.annotationType().isAnnotationPresent(Constraint.class))
                            .map(a -> a.annotationType().getSimpleName()).toList());
                    info.put("cascade", field.isAnnotationPresent(Valid.class));
                    fields.putIfAbsent(field.getName(), info);
                    if (type.getSimpleName().contains("ReqVO") && !field.isAnnotationPresent(Valid.class)) {
                        Class<?> nested = nestedType(field.getGenericType());
                        if (nested != null && nested.getName().startsWith("cn.iocoder.") && constrained(nested)) {
                            missingCascade.add(Map.of("class", type.getName(), "field", field.getName(), "owner", owner.getName(), "nested", nested.getName()));
                        }
                    }
                }
            }
            models.put(type.getName(), Map.of("canonicalName", type.getCanonicalName(), "fields", fields));
        }
        assertTrue(missingCascade.isEmpty(), "Nested constraints need @Valid on the parent field: " + missingCascade);
        assertTrue(models.size() > 2000, "All enabled controller models must be scanned");
        Files.writeString(Path.of("target/full-model-inventory.json"), io.swagger.v3.core.util.Json.mapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("models", models, "missingCascade", missingCascade)));
        System.out.println("Full model inventory: " + models.size() + "; cascade candidates: " + missingCascade.size());
    }

    private static Class<?> nestedType(Type type) {
        if (type instanceof Class<?> clazz) return clazz;
        if (type instanceof ParameterizedType generic && generic.getActualTypeArguments().length == 1)
            return nestedType(generic.getActualTypeArguments()[0]);
        return null;
    }

    private static boolean constrained(Class<?> type) {
        for (Class<?> owner = type; owner != Object.class && owner != null; owner = owner.getSuperclass()) {
            for (Field field : owner.getDeclaredFields()) {
                for (Annotation annotation : field.getAnnotations()) {
                    if (annotation.annotationType().isAnnotationPresent(Constraint.class)) return true;
                }
            }
        }
        return false;
    }
}
