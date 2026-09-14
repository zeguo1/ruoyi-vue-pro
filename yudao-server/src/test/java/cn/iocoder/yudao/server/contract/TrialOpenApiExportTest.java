package cn.iocoder.yudao.server.contract;

import cn.iocoder.yudao.module.crm.controller.admin.trial.*;
import cn.iocoder.yudao.module.crm.framework.trial.TrialOpenApiCustomizer;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.jackson.TypeNameResolver;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Actual Springdoc generation with inert controllers, no business databases, sessions or network requests. */
class TrialOpenApiExportTest {
    @Configuration(proxyBeanMethods = false)
    @Import(FullOpenApiExportTest.Documentation.class)
    static class Documentation {
        @Bean TrialOpenApiCustomizer trialOpenApiCustomizer() { return new TrialOpenApiCustomizer(); }
    }

    @Test void generatedToolsHaveStableIdsTrustedAuthAndDistinctReadiness() throws Exception {
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
                    "spring.autoconfigure.exclude=" + String.join(",", excluded), "mgs.trial.storage-enabled=true",
                    "springdoc.api-docs.version=OPENAPI_3_1", "springdoc.use-fqn=true", "springdoc.api-docs.enabled=true", "springdoc.default-flat-param-object=true");
            context.register(Documentation.class);
            var objects = new ObjenesisStd();
            context.addBeanFactoryPostProcessor(factory -> {
                for (Class<?> type : List.of(TrialToolController.class, TrialEventController.class, TrialBusinessController.class,
                        TrialOperationsController.class, TrialAuthorizationController.class, TrialLoginDeliveryController.class)) {
                    factory.registerSingleton(type.getName(), objects.newInstance(type));
                }
            });
            context.refresh();
            context.getBean(io.swagger.v3.oas.models.OpenAPI.class).getInfo().version("mgs-trial-v1-candidate");
            var response = MockMvcBuilders.webAppContextSetup(context).build().perform(get("/v3/api-docs/all")).andReturn().getResponse();
            assertEquals(200, response.getStatus());
            String json = response.getContentAsString(StandardCharsets.UTF_8);
            JsonNode doc = io.swagger.v3.core.util.Json.mapper().readTree(json);
            Files.writeString(Path.of("target/trial-openapi.json"), json);
            assertFalse(doc.path("paths").has("/admin-api/crm/trial-internal/authorization"));
            assertFalse(doc.path("paths").has("/admin-api/crm/trial-internal/login-delivery"));
            assertFalse(json.contains("TrialLoginDeliveryService"), "Login credentials must not leak into the tool catalog");
            assertFalse(json.contains("TrialOAuthGateway"), "Credential schemas must not leak into the tool catalog");
            var tools = Map.of("submit", "submit_trial_application", "create-accounts", "create_trial_accounts", "status", "get_trial_status", "guide", "get_trial_guide");
            for (var entry : tools.entrySet()) {
                JsonNode operation = doc.path("paths").path("/admin-api/crm/trial-tool/" + entry.getKey()).path("post");
                assertEquals(entry.getValue(), operation.path("operationId").asText());
                assertEquals(Set.of("status", "guide").contains(entry.getKey()), operation.path("x-mgs-read-only").asBoolean());
                for (var parameter : operation.path("parameters")) {
                    assertFalse(Set.of("tenant-id", "Authorization").contains(parameter.path("name").asText()));
                }
                assertTrue(operation.path("security").get(0).has("MgsTrialSignature"));
                assertEquals("https", operation.path("x-mgs-service-contract").path("transport").asText());
                assertTrue(operation.path("x-mgs-service-contract").path("secureRequestRequired").isBoolean());
                assertTrue(operation.path("x-mgs-service-contract").path("secureRequestRequired").asBoolean());
                var conditions = operation.path("x-mgs-account-ready-condition").path("all");
                assertTrue(conditions.get(0).path("equals").isInt());
                assertEquals(0, conditions.get(0).path("equals").asInt());
                assertEquals("data.accountReady", conditions.get(1).path("path").asText());
                assertTrue(conditions.get(1).path("equals").isBoolean());
                JsonNode body = resolve(doc, operation.path("requestBody").path("content").path("application/json").path("schema"));
                assertFalse(body.path("additionalProperties").asBoolean(true));
                assertFalse(body.path("properties").has("subjectId"));
                assertFalse(body.path("properties").has("tenantId"));
                if (entry.getKey().equals("submit")) {
                    assertTrue(body.path("properties").has("team"));
                    assertTrue(body.path("required").toString().contains("contactName"));
                } else {
                    assertTrue(body.path("required").toString().contains("applicationId"));
                }
                JsonNode envelope = resolve(doc, operation.path("responses").path("200").path("content").path("application/json").path("schema"));
                assertTrue(envelope.path("properties").has("code"));
                JsonNode data = resolve(doc, envelope.path("properties").path("data"));
                assertTrue(data.path("properties").has("accountReady"), entry.getKey());
            }
            var eventContract = doc.path("paths").path("/admin-api/crm/trial-event/accept").path("post").path("x-mgs-service-contract");
            assertEquals("https", eventContract.path("transport").asText());
            assertTrue(eventContract.path("secureRequestRequired").asBoolean());
        }
    }

    private JsonNode resolve(JsonNode document, JsonNode schema) {
        if (schema.has("$ref")) {
            String reference = schema.path("$ref").asText();
            return document.path("components").path("schemas").path(reference.substring(reference.lastIndexOf('/') + 1));
        }
        if (schema.has("allOf")) return resolve(document, schema.path("allOf").get(0));
        if (schema.has("anyOf")) {
            for (var candidate : schema.path("anyOf")) {
                if (candidate.has("$ref")) return resolve(document, candidate);
            }
        }
        return schema;
    }
}
