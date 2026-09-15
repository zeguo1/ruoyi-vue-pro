package cn.iocoder.yudao.module.crm.framework.trial;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.*;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import java.util.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
public class TrialConnectorOpenApiConfiguration {
    @Bean public GroupedOpenApi trialConnectorApi() {
        return GroupedOpenApi.builder().group("trial-connector").pathsToMatch("/admin-api/crm/trial-connector/**").build();
    }
    @Bean public GlobalOpenApiCustomizer trialConnectorContract() {
        return document -> {
            if (document.getPaths() == null || document.getPaths().keySet().stream().noneMatch(p -> p.startsWith("/admin-api/crm/trial-connector/"))) return;
            if (document.getComponents() == null) document.setComponents(new Components());
            document.getComponents().addSecuritySchemes("MgsTrialConnectorBearer", new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("opaque service token")
                    .description("仅专用 TOOLS 服务 Token，不能使用个人或管理员登录 Token；服务端保管并经 HTTPS 注入。"));
            document.getPaths().forEach((path, item) -> {
                if (!path.startsWith("/admin-api/crm/trial-connector/")) return;
                item.readOperations().forEach(operation -> {
                    if (operation.getParameters() != null) operation.getParameters().removeIf(p -> "header".equals(p.getIn())
                            && Set.of("authorization", "tenant-id", "visit-tenant-id", "x-knowdo-context").contains(p.getName().toLowerCase(Locale.ROOT)));
                    operation.setSecurity(List.of(new SecurityRequirement().addList("MgsTrialConnectorBearer")));
                    operation.addExtension("x-mgs-service-contract", Map.of("version", "mgs-trial-connector-v1", "capability", "TOOLS",
                            "transport", "https", "trustedHeaders", List.of("X-KnowDo-Context"), "modelMaySupplyIdentity", false,
                            "contextEncoding", "base64url(JSON): version=1, actorId, audience, assistantId, conversationId, taskId, channel, channelId?, operationId"));
                    operation.addExtension("x-mgs-read-only", path.endsWith("/status") || path.endsWith("/guide"));
                    operation.addExtension("x-mgs-account-ready-condition", Map.of("all", List.of(
                            Map.of("path", "code", "equals", 0), Map.of("path", "data.accountReady", "equals", true))));
                    operation.addExtension("x-mgs-contact-and-consent", "手机号验证及当前申请确认由 MGS 保存；调用身份、工具参数或工具权限均不代表验证或确认");
                });
            });
        };
    }
}
