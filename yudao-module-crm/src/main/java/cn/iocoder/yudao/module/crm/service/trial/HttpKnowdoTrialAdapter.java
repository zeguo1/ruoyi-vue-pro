package cn.iocoder.yudao.module.crm.service.trial;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
@RequiredArgsConstructor
public class HttpKnowdoTrialAdapter implements KnowdoTrialAdapter {
    private final TrialProperties properties;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private static final Map<String, Set<String>> IDS = Map.of(
            "KNOWDO_MEMBER", Set.of("knowdoTenantId", "knowdoMemberId"),
            "KNOWDO_AUTH", Set.of("authorizationId"),
            "DELIVERY", Set.of("deliveryRef"),
            "KNOWDO_REVOKE", Set.of("revocationId"));

    @Override
    public Result lookup(TrialStore.Application app, String step) { return call(app, step, "lookup", Map.of()); }
    @Override
    public Result ensure(TrialStore.Application app, String step, Map<String, String> resources) {
        Map<String, String> permitted = new HashMap<>();
        for (String name : Set.of("tenantId", "userId", "customerId", "mgsAuthorizationRef", "knowdoTenantId", "knowdoMemberId", "authorizationId")) {
            if (resources.containsKey(name)) { permitted.put(name, resources.get(name)); }
        }
        return call(app, step, "ensure", permitted);
    }

    private Result call(TrialStore.Application app, String step, String action, Map<String, String> resources) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("Network I/O inside trial transaction"); }
        String base = app.policy().knowdoBaseUrl();
        if (base == null || !base.startsWith("https://") || properties.getOutboundKeyId() == null
                || properties.getOutboundSecret() == null || properties.getOutboundSecret().length() < 32) {
            throw TrialException.unavailable();
        }
        try {
            URI uri = URI.create(base.replaceAll("/$", "") + "/internal/mgs-trials/v1/" + action);
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) { throw TrialException.unavailable(); }
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString();
            String body = json.writeValueAsString(Map.of("contractVersion", "mgs-trial-v1", "applicationId", app.id(),
                    "idempotencyKey", app.id() + ":" + step, "step", step, "issuer", app.issuer(),
                    "subjectId", app.subjectId(), "expiresAt", app.expiresAt().toString(), "resources", resources));
            String canonical = String.join("\n", "mgs-trial-outbound-v1", properties.getOutboundKeyId(), timestamp, nonce,
                    "POST", uri.getRawPath(), TrialServiceAuth.sha256(body));
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json").header("X-Mgs-Trial-Key", properties.getOutboundKeyId())
                    .header("X-Mgs-Trial-Timestamp", timestamp).header("X-Mgs-Trial-Nonce", nonce)
                    .header("X-Mgs-Trial-Signature", TrialServiceAuth.hmac(properties.getOutboundSecret(), canonical))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            var pending = http.sendAsync(request, ignored -> new BoundedResponseBody());
            HttpResponse<byte[]> response;
            try {
                // Bound the complete body, not just connection/response headers, and cancel stalled requests.
                response = pending.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                pending.cancel(true);
                throw e;
            }
            byte[] bytes = response.body();
            // Unknown routes, authorization errors and timeouts are never interpreted as "not created".
            if (response.statusCode() != 200 || bytes.length > 16_384) { throw new IllegalStateException("KnowDo transport incomplete"); }
            JsonNode node = json.readTree(bytes);
            if (!"mgs-trial-v1".equals(node.path("contractVersion").asText()) || !app.id().equals(node.path("applicationId").asText())
                    || !step.equals(node.path("step").asText())) { throw new IllegalStateException("KnowDo contract mismatch"); }
            LookupState state = LookupState.valueOf(node.path("state").asText());
            Map<String, String> ids = new HashMap<>();
            if (state == LookupState.COMPLETE) {
                for (String field : IDS.get(step)) {
                    JsonNode value = node.path("resourceIds").path(field);
                    if (!value.isTextual() || !value.asText().matches("[a-zA-Z0-9_.:-]{1,128}")) {
                        throw new IllegalStateException("KnowDo resource evidence missing");
                    }
                    ids.put(field, value.asText());
                }
                if ("KNOWDO_REVOKE".equals(step) && !node.path("allBusinessAccessRevoked").asBoolean(false)) {
                    throw new IllegalStateException("KnowDo revocation incomplete");
                }
            }
            return new Result(state, ids);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("KnowDo request interrupted");
        } catch (Exception e) {
            // Do not expose response content, URL credentials or raw exceptions in tool results/logs.
            throw new IllegalStateException("KnowDo trial step requires reconciliation");
        }
    }

    static final class BoundedResponseBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription incoming) {
            if (subscription != null) { incoming.cancel(); return; }
            subscription = incoming;
            incoming.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > 16_384 - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalStateException("KnowDo response exceeds limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable ignored) {
            result.completeExceptionally(new IllegalStateException("KnowDo response incomplete"));
        }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }

}
