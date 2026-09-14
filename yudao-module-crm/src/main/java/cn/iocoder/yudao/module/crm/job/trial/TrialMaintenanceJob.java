package cn.iocoder.yudao.module.crm.job.trial;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import cn.iocoder.yudao.framework.quartz.core.handler.JobHandler;
import cn.iocoder.yudao.module.crm.service.trial.TrialOrchestrator;
import cn.iocoder.yudao.module.crm.service.trial.TrialStore;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

@Component
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
@RequiredArgsConstructor
public class TrialMaintenanceJob implements JobHandler {
    private final TrialStore store;
    private final TrialOrchestrator orchestrator;
    private final JdbcTemplate jdbc;
    @Override
    public String execute(String param) {
        var pending = store.pending(20);
        for (var app : pending) { orchestrator.advance(app.id()); }
        jdbc.update("DELETE FROM crm_trial_nonce WHERE expires_at < ?", Timestamp.from(Instant.now()));
        jdbc.update("UPDATE crm_trial_login_delivery SET ciphertext=NULL,nonce=NULL WHERE ciphertext IS NOT NULL AND (retry_until<=? OR application_id IN (SELECT id FROM crm_trial_application WHERE expires_at<=?))",
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        return "已检查 " + pending.size() + " 个试用申请";
    }
}
