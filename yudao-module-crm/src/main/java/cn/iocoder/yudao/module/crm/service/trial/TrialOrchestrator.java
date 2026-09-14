package cn.iocoder.yudao.module.crm.service.trial;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
@RequiredArgsConstructor
public class TrialOrchestrator {
    private final TrialStore store;
    private final TrialLocalProvisioner local;
    private final KnowdoTrialAdapter knowdo;

    /** Explicit command / durable job only. Status queries never call this method. */
    public void advance(String id) {
        TrialStore.Application app = store.get(id);
        if (app == null || "EXPIRED".equals(app.status())) { return; }
        store.touch(id); // Rotate failed applications fairly through the bounded maintenance batch.
        boolean expired = !app.expiresAt().isAfter(Instant.now());
        if (expired) { store.locked(id, current -> { store.status(id, "REVOKING"); return null; }); }
        boolean allRevoked = true;
        for (String step : expired ? TrialStore.REVOKE_STEPS : TrialStore.PROVISION_STEPS) {
            if (!expired && !"CRM".equals(step) && app.confirmedAt() == null) { return; }
            if (step.startsWith("KNOWDO") || "DELIVERY".equals(step)) {
                if (!remote(id, step, expired)) { if (!expired) { return; } allRevoked = false; }
            } else if (!local(id, step, expired)) { if (!expired) { return; } allRevoked = false; }
        }
        if (expired && !allRevoked) { return; }
        store.locked(id, current -> {
            // Re-evaluate expiry after network calls, before readiness is published.
            if (expired) { store.status(id, "EXPIRED"); }
            else if (current.expiresAt().isAfter(Instant.now())) { store.status(id, "READY"); }
            else { store.status(id, "REVOKING"); }
            return null;
        });
    }

    private boolean local(String id, String name, boolean revoke) {
        try {
            return store.locked(id, app -> {
                if (!revoke && !app.expiresAt().isAfter(Instant.now())) { return false; }
                if ("DONE".equals(store.step(id, name).state())) { return true; }
                store.localDone(id, name, local.execute(app, name, resources(id)));
                return true;
            });
        } catch (RuntimeException e) {
            // Deliberately do not persist/log arbitrary exception messages containing contacts or credentials.
            store.locked(id, app -> { store.failed(id, name); return null; });
            return false;
        }
    }

    private boolean remote(String id, String name, boolean revoke) {
        String lease = store.locked(id, app -> {
            if (!revoke && (!app.expiresAt().isAfter(Instant.now()) || "REVOKING".equals(app.status()))) { return null; }
            if ("DONE".equals(store.step(id, name).state())) { return null; }
            return store.claim(id, name);
        });
        if (lease == null) { return "DONE".equals(store.step(id, name).state()); }
        try {
            TrialStore.Application app = store.get(id);
            // Always reconcile first, including the first attempt. A timeout is never treated as absence.
            KnowdoTrialAdapter.Result result = knowdo.lookup(app, name);
            if (result.state() == KnowdoTrialAdapter.LookupState.ABSENT) {
                if (!revoke && !app.expiresAt().isAfter(Instant.now())) {
                    store.uncertain(id, name, lease, "EXPIRED_BEFORE_REMOTE_WRITE"); return false;
                }
                result = knowdo.ensure(app, name, resources(id));
            }
            if (result.state() != KnowdoTrialAdapter.LookupState.COMPLETE) {
                store.uncertain(id, name, lease, "REMOTE_PENDING"); return false;
            }
            return store.finish(id, name, lease, result.resourceIds());
        } catch (RuntimeException e) {
            store.uncertain(id, name, lease, "REMOTE_RECONCILIATION_REQUIRED");
            return false;
        }
    }

    public Map<String, String> resources(String id) {
        Map<String, String> resources = new HashMap<>();
        store.steps(id).stream().filter(s -> "DONE".equals(s.state())).forEach(s -> resources.putAll(s.result()));
        return resources;
    }
}
