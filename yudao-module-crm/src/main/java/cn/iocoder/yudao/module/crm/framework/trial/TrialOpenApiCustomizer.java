package cn.iocoder.yudao.module.crm.framework.trial;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.Map;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
public class TrialOpenApiCustomizer implements GlobalOpenApiCustomizer {
    private static final Map<String, String> TOOLS = Map.of("submit", "submit_trial_application", "create-accounts", "create_trial_accounts",
            "status", "get_trial_status", "guide", "get_trial_guide");
    @Override public void customise(OpenAPI document) {
        if (document.getPaths() == null || document.getPaths().keySet().stream().noneMatch(path ->
                path.startsWith("/admin-api/crm/trial-tool/") || path.startsWith("/admin-api/crm/trial-business/")
                        || path.equals("/admin-api/crm/trial-event/accept"))) { return; }
        document.addExtension("x-mgs-trial-contract-version", "mgs-trial-v1-candidate");
        if (document.getComponents() == null) { document.setComponents(new Components()); }
        document.getComponents().addSecuritySchemes("MgsTrialSignature", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER).name("X-Mgs-Trial-Signature")
                .description("仅限 HTTPS 安全请求的 HMAC-SHA256 服务签名，不是静态 API Key。可信会话身份与完整签名 header 由服务端注入；见 mgs-trial-v1 契约。"));
        document.getPaths().forEach((path, item) -> {
            if (path.startsWith("/admin-api/crm/trial-business/")) {
                document.getComponents().addSecuritySchemes("MgsTrialPersonalBearer", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer")
                        .description("当前申请人本人的 MGS 访问令牌，由知办服务端个人凭据存储注入；不得使用共享管理员令牌或模型输入。"));
                item.readOperations().forEach(operation -> {
                    if (operation.getParameters() != null) {
                        operation.getParameters().removeIf(p -> "header".equals(p.getIn())
                                && List.of("tenant-id", "Authorization").contains(p.getName()));
                    }
                    var tenant = new Parameter().name("tenant-id").in("header").required(false)
                            .schema(new IntegerSchema().format("int64"))
                            .description("可省略，租户过滤器将从已认证用户取得租户。若传入，由连接器服务端固定配置，必须与当前用户及本人试用申请一致；当前演示租户为 1，不能由模型选择。")
                            .example(1);
                    tenant.addExtension("x-mgs-value-source", "connector-configuration");
                    tenant.addExtension("x-mgs-model-input", false);
                    tenant.addExtension("x-mgs-omitted-value-source", "authenticated-user");
                    operation.addParametersItem(tenant);
                    operation.setSecurity(List.of(new SecurityRequirement().addList("MgsTrialPersonalBearer")));
                    operation.addExtension("x-mgs-personal-authorization", Map.of("credentialOwner", "current-user",
                            "modelMaySupplyCredentials", false, "tenantSource", "connector-configuration"));
                });
                return;
            }
            boolean tool = path.startsWith("/admin-api/crm/trial-tool/");
            boolean event = path.equals("/admin-api/crm/trial-event/accept");
            if (!tool && !event) { return; }
            item.readOperations().forEach(operation -> {
                if (operation.getParameters() != null) {
                    operation.getParameters().removeIf(p -> "header".equals(p.getIn()) && List.of("tenant-id", "Authorization").contains(p.getName()));
                }
                operation.setSecurity(List.of(new SecurityRequirement().addList("MgsTrialSignature")));
                operation.addExtension("x-mgs-service-contract", Map.of("version", "mgs-trial-v1", "capability", tool ? "TOOLS" : "EVENTS",
                        "transport", "https", "secureRequestRequired", true,
                        "modelMaySupplyIdentity", false, "trustedHeaders", List.of("X-Mgs-Trial-Key", "X-Mgs-Trial-Timestamp", "X-Mgs-Trial-Nonce",
                                "X-Mgs-Trial-Subject", "X-Mgs-Trial-Verified", "X-Mgs-Trial-Email", "X-Mgs-Trial-Confirmation", "X-Mgs-Trial-Idempotency", "X-Mgs-Trial-Signature")));
                if (tool) {
                    String action = path.substring(path.lastIndexOf('/') + 1);
                    operation.setOperationId(TOOLS.get(action));
                    operation.addExtension("x-mgs-read-only", action.equals("status") || action.equals("guide"));
                    operation.addExtension("x-mgs-account-ready-condition", Map.of("all", List.of(
                            Map.of("path", "code", "equals", 0), Map.of("path", "data.accountReady", "equals", true))));
                    operation.addExtension("x-mgs-success-meaning", "code=0 仅表示本次请求处理成功；账号就绪按 x-mgs-account-ready-condition，公众号绑定与业务完成独立跟踪");
                }
            });
        });
    }
}
