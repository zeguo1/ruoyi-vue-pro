package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.crm.controller.admin.trial.vo.TrialEventReqVO;
import cn.iocoder.yudao.module.crm.enums.common.CrmBizTypeEnum;
import cn.iocoder.yudao.module.crm.service.followup.CrmFollowUpRecordService;
import cn.iocoder.yudao.module.crm.service.followup.bo.CrmFollowUpCreateReqBO;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
@RequiredArgsConstructor
public class TrialEventService {
    private final TrialStore store;
    private final JdbcTemplate jdbc;
    private final CrmFollowUpRecordService followUps;

    public void accept(TrialIdentity identity, TrialEventReqVO event) {
        store.locked(event.getApplicationId(), app -> {
            store.owned(app.id(), identity);
            if (!Objects.equals(event.getKnowdoMemberId(), store.step(app.id(), "KNOWDO_MEMBER").result().get("knowdoMemberId"))) {
                throw TrialException.notFound();
            }
            String hash = TrialServiceAuth.sha256(store.encode(event));
            List<Map<String, Object>> previous = jdbc.queryForList("SELECT payload_hash FROM crm_trial_event WHERE issuer=? AND event_id=?",
                    identity.issuer(), event.getEventId());
            if (!previous.isEmpty()) {
                if (!hash.equals(previous.get(0).get("payload_hash"))) { throw TrialException.conflict(); }
                return null;
            }
            if (!app.expiresAt().isAfter(Instant.now()) || !"READY".equals(app.status())) {
                throw TrialException.error(8, "体验账号未就绪或已到期");
            }
            boolean firstBusiness = "FIRST_BUSINESS_COMPLETED".equals(event.getType());
            if (firstBusiness) {
                long businessRecord;
                try { businessRecord = Long.parseLong(event.getBusinessRecordId()); }
                catch (RuntimeException e) { throw TrialException.error(10, "缺少有效业务记录编号"); }
                Long count = jdbc.queryForObject("SELECT COUNT(*) FROM crm_trial_business_operation WHERE application_id=? AND follow_up_id=?",
                        Long.class, app.id(), businessRecord);
                Timestamp bound = jdbc.queryForObject("SELECT bound_at FROM crm_trial_application WHERE id=?", Timestamp.class, app.id());
                if (count == null || count == 0 || bound == null) { throw TrialException.error(10, "缺少绑定或实际业务完成依据"); }
            }
            String field = firstBusiness ? "first_business_at" : "bound_at"; // Closed enum; never a request field name.
            int updated = jdbc.update("UPDATE crm_trial_application SET " + field + "=? WHERE id=? AND " + field + " IS NULL",
                    Timestamp.from(Instant.now()), app.id());
            jdbc.update("INSERT INTO crm_trial_event(issuer,event_id,application_id,event_type,payload_hash,created_at) VALUES(?,?,?,?,?,?)",
                    identity.issuer(), event.getEventId(), app.id(), event.getType(), hash, Timestamp.from(Instant.now()));
            if (updated == 1) {
                String clueId = store.step(app.id(), "CRM").result().get("clueId");
                TenantUtils.execute(app.policy().operatorTenantId(), () -> {
                    var summary = new CrmFollowUpCreateReqBO();
                    summary.setBizType(CrmBizTypeEnum.CRM_CLUE.getType()); summary.setBizId(Long.parseLong(clueId));
                    summary.setContent(firstBusiness ? "试用申请已完成首次演示客户跟进业务" : "试用申请已完成公众号账号绑定（不代表已完成业务）");
                    // Internal batch API supports system summaries without inventing a user's next contact date/type.
                    followUps.createFollowUpRecordBatch(List.of(summary));
                });
            }
            return null;
        });
    }
}
