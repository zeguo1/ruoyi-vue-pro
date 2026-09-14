package cn.iocoder.yudao.server.contract;

import cn.iocoder.yudao.framework.swagger.config.ContractSchemaCustomizer;
import io.swagger.v3.core.converter.*;
import io.swagger.v3.core.jackson.TypeNameResolver;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExternalOpenApiContractTest {
    @Test void sdkSchemasHaveVerifiedDescriptionsAndNoCopyHelperFields() throws Exception {
        TypeNameResolver.std.setUseFqn(true);
        var mapper = io.swagger.v3.core.util.Json.mapper();
        var metadata = mapper.readTree(new ClassPathResource("openapi/external-schema-descriptions.json").getInputStream());
        var fields = metadata.fields(); int models = 0;
        while (fields.hasNext()) {
            var entry = fields.next(); String name = entry.getKey(); Class<?> type;
            try { type = Class.forName(name); }
            catch (ClassNotFoundException e) { int last = name.lastIndexOf('.'); type = Class.forName(name.substring(0,last) + "$" + name.substring(last+1)); }
            var result = ModelConverters.getInstance(true).resolveAsResolvedSchema(new AnnotatedType(type).resolveAsRef(true));
            var api = new OpenAPI().components(new Components().schemas(result.referencedSchemas));
            new ContractSchemaCustomizer().customise(api);
            Schema<?> schema = api.getComponents().getSchemas().get(name);
            assertNotNull(schema,name);
            for (var property : schema.getProperties().entrySet()) {
                assertNotNull(property.getValue().getDescription(),name + "." + property.getKey());
                assertFalse(property.getValue().getDescription().isBlank(),name + "." + property.getKey());
            }
            for (String hidden : entry.getValue().path("@hide").asText().split(",")) {
                if (hidden.isEmpty()) continue;
                assertFalse(schema.getProperties().containsKey(hidden),name + "." + hidden);
                // These are SDK helper setters, not getters in actual serialized responses.
                assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(type.getConstructor().newInstance()).has(hidden),name);
            }
            models++;
        }
        assertEquals(19,models);
    }
}
