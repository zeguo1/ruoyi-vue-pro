package cn.iocoder.yudao.server.contract;

import cn.iocoder.yudao.framework.swagger.config.ContractSchemaCustomizer;
import io.swagger.v3.core.jackson.TypeNameResolver;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.context.annotation.*;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockServletContext;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Documentation-only context. Controllers are never invoked; no databases, Redis or business services exist. */
class FullOpenApiExportTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration.class,
            cn.iocoder.yudao.module.crm.framework.trial.TrialConnectorOpenApiConfiguration.class})
    static class Documentation implements WebMvcConfigurer {
        @Bean org.springdoc.core.providers.JavadocProvider javadocProvider() { return new cn.iocoder.yudao.framework.swagger.config.ContractJavadocProvider(); }
        @Bean io.swagger.v3.oas.models.OpenAPI apiInfo() { return new io.swagger.v3.oas.models.OpenAPI().info(new io.swagger.v3.oas.models.info.Info().title("MGS integration candidate (not deployed)").version("1.0.0-full-contract-v7.3")); }
        @Bean org.springdoc.core.models.GroupedOpenApi all() { return cn.iocoder.yudao.framework.swagger.config.YudaoSwaggerAutoConfiguration.buildGroupedOpenApi("all", ""); }
        @Bean ContractSchemaCustomizer contractSchemaCustomizer() { return new ContractSchemaCustomizer(); }
        @Override public void configurePathMatch(PathMatchConfigurer configurer) {
            configurer.addPathPrefix("/admin-api", c -> c.getPackageName().contains(".controller.admin."));
            configurer.addPathPrefix("/app-api", c -> c.getPackageName().contains(".controller.app."));
        }
    }

    @Test void exportAllControllersWithoutBusinessInfrastructure() throws Exception {
        var excluded = new ArrayList<String>();
        for (String name : ImportCandidates.load(org.springframework.boot.autoconfigure.AutoConfiguration.class, getClass().getClassLoader())) {
            if (!(name.startsWith("org.springdoc.") || name.startsWith("org.springframework.boot.autoconfigure.jackson.")
                    || name.startsWith("org.springframework.boot.autoconfigure.http.")
                    || name.equals("org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration")
                    || name.startsWith("org.springframework.boot.autoconfigure.validation."))) excluded.add(name);
        }
        TypeNameResolver.std.setUseFqn(true);
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "spring.autoconfigure.exclude=" + String.join(",", excluded),
                    "mgs.trial.storage-enabled=true",
                    "spring.jackson.serialization.write-dates-as-timestamps=true", "spring.jackson.serialization.write-date-timestamps-as-nanoseconds=false", "springdoc.api-docs.version=OPENAPI_3_1", "springdoc.use-fqn=true", "springdoc.api-docs.enabled=true", "springdoc.default-flat-param-object=true");
            context.register(Documentation.class);
            // Register instances directly to bypass dependency injection and all business initialization.
            var scanner = new ClassPathScanningCandidateComponentProvider(false);
            scanner.setEnvironment(context.getEnvironment());
            scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
            var objects = new ObjenesisStd();
            var controllers = new TreeMap<String, Object>();
            for (String base : List.of("cn.iocoder.yudao.module", "org.jeecg")) {
                for (var bean : scanner.findCandidateComponents(base)) {
                    Class<?> type = Class.forName(bean.getBeanClassName(), false, getClass().getClassLoader());
                    controllers.put(type.getName(), objects.newInstance(type));
                }
            }
            context.addBeanFactoryPostProcessor(factory -> controllers.forEach(factory::registerSingleton));
            context.refresh();
            String json = MockMvcBuilders.webAppContextSetup(context).build().perform(get("/v3/api-docs"))
                    .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            String allJson = mvc.perform(get("/v3/api-docs/all")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            Files.writeString(Path.of("target/openapi-integration-all.json"), allJson);
            String connectorJson = mvc.perform(get("/v3/api-docs/trial-connector")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            var connectorTree = io.swagger.v3.core.util.Json.mapper().readTree(connectorJson);
            assertEquals(4, connectorTree.path("paths").size());
            var allTree = io.swagger.v3.core.util.Json.mapper().readTree(allJson);
            allTree.path("paths").fieldNames().forEachRemaining(path -> assertFalse(path.contains("/trial-settings"), "Control-plane credentials must not be Agent tools"));
            connectorTree.path("paths").fieldNames().forEachRemaining(path -> assertTrue(path.startsWith("/admin-api/crm/trial-connector/")));
            assertEquals("bearer", connectorTree.path("components").path("securitySchemes").path("MgsTrialConnectorBearer").path("scheme").asText());
            connectorTree.path("paths").forEach(item -> item.forEach(op -> {
                assertTrue(op.path("security").get(0).has("MgsTrialConnectorBearer"));
                assertEquals("code", op.path("x-business-success").path("path").asText());
                assertTrue(op.path("x-business-success").path("equals").isIntegralNumber());
                assertEquals(0, op.path("x-business-success").path("equals").asInt());
                assertTrue(op.path("requestBody").path("required").asBoolean());
                assertTrue(op.path("requestBody").path("content").has("application/json"));
                op.path("parameters").forEach(parameter -> assertNotEquals("X-KnowDo-Context", parameter.path("name").asText()));
            }));
            Files.writeString(Path.of("target/openapi-trial-connector.json"), connectorJson);
            var tree = io.swagger.v3.core.util.Json.mapper().readTree(json);
            assertTrue(tree.path("paths").size() > 2000, "All controller paths must be exported: " + tree.path("paths").size());
            Files.writeString(Path.of("target/full-openapi-isolated.json"), json);
            var schemas = tree.path("components").path("schemas");
            schemas.fields().forEachRemaining(entry -> {
                if (!entry.getKey().startsWith("cn.iocoder.")) return;
                entry.getValue().path("properties").fields().forEachRemaining(field ->
                        assertFalse(field.getValue().path("description").asText().isBlank(),
                                entry.getKey() + "." + field.getKey() + " lacks a field description"));
            });
            tree.path("paths").fields().forEachRemaining(path -> {
                if (!path.getKey().startsWith("/admin-api/") && !path.getKey().startsWith("/app-api/")) return;
                path.getValue().fields().forEachRemaining(method -> {
                    if (Set.of("get", "post", "put", "patch", "delete", "head", "options").contains(method.getKey()))
                        assertFalse(method.getValue().path("summary").asText().isBlank(), path.getKey());
                });
            });
            var contracts = io.swagger.v3.core.util.Json.mapper().readTree(Files.readString(Path.of("../script/openapi/full-update-contracts.json")));
            var additional = io.swagger.v3.core.util.Json.mapper().readTree(Files.readString(Path.of("../script/openapi/v5-update-contracts.json")));
            ((com.fasterxml.jackson.databind.node.ArrayNode) contracts).addAll((com.fasterxml.jackson.databind.node.ArrayNode) additional);
            for (var contract : contracts) {
                String schemaName = contract.path("model").asText().split("/src/main/java/")[1].replace('/', '.').replace(".java", "");
                var schema = schemas.path(schemaName);
                assertFalse(schema.isMissingNode(), schemaName);
                schema.path("required").forEach(required -> assertNotEquals("id", required.asText(), schemaName));
            }
            var handlerInventory = new TreeMap<String, Object>();
            for (var controller : controllers.values()) {
                for (var method : controller.getClass().getDeclaredMethods()) {
                    var arguments = new ArrayList<Map<String, Object>>();
                    for (var argument : method.getParameters()) {
                        var requestParam = argument.getAnnotation(org.springframework.web.bind.annotation.RequestParam.class);
                        var pathVariable = argument.getAnnotation(org.springframework.web.bind.annotation.PathVariable.class);
                        var requestBody = argument.getAnnotation(org.springframework.web.bind.annotation.RequestBody.class);
                        if (requestParam == null && pathVariable == null && requestBody == null) continue;
                        String name = argument.getName();
                        if (requestParam != null) name = !requestParam.name().isBlank() ? requestParam.name() : !requestParam.value().isBlank() ? requestParam.value() : name;
                        if (pathVariable != null) name = !pathVariable.name().isBlank() ? pathVariable.name() : !pathVariable.value().isBlank() ? pathVariable.value() : name;
                        String in = requestBody != null ? "requestBody" : pathVariable != null ? "path" : "query";
                        boolean required = requestBody != null ? requestBody.required() : pathVariable != null || requestParam.required()
                                && org.springframework.web.bind.annotation.ValueConstants.DEFAULT_NONE.equals(requestParam.defaultValue());
                        arguments.add(Map.of("name", name, "in", in, "javaType", argument.getParameterizedType().getTypeName(), "required", required,
                                "mvcRequired", pathVariable != null ? pathVariable.required() : required));
                    }
                    handlerInventory.put(method.toGenericString(), arguments);
                }
            }
            Files.writeString(Path.of("target/openapi-handler-inventory.json"), io.swagger.v3.core.util.Json.mapper().writeValueAsString(handlerInventory));
            System.out.println("Isolated OpenAPI: " + controllers.size() + " controllers; " + tree.path("paths").size() + " paths");
        }
    }
}
