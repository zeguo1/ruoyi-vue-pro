package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.crm.controller.admin.followup.vo.CrmFollowUpRecordSaveReqVO;
import cn.iocoder.yudao.module.crm.controller.admin.trial.vo.TrialFollowUpReqVO;
import cn.iocoder.yudao.module.crm.dal.dataobject.customer.CrmCustomerDO;
import cn.iocoder.yudao.module.crm.dal.dataobject.followup.CrmFollowUpRecordDO;
import cn.iocoder.yudao.module.crm.enums.common.CrmBizTypeEnum;
import cn.iocoder.yudao.module.crm.framework.trial.TrialBusinessAccess;
import cn.iocoder.yudao.module.crm.service.customer.CrmCustomerService;
import cn.iocoder.yudao.module.crm.service.followup.CrmFollowUpRecordService;
import cn.iocoder.yudao.module.system.api.dict.DictDataApi;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
@RequiredArgsConstructor
public class TrialBusinessService {
    private final TrialBusinessAccess access;
    private final TrialStore store;
    private final CrmCustomerService customers;
    private final CrmFollowUpRecordService followUps;
    private final DictDataApi dict;
    private final JdbcTemplate jdbc;

    public CrmCustomerDO customer() { return customer(access.requireCurrent()); }
    private CrmCustomerDO customer(TrialStore.Application app) {
        String id = store.step(app.id(), "DEMO").result().get("customerId");
        CrmCustomerDO customer = id == null ? null : customers.getCustomer(Long.parseLong(id));
        // An accidental CRM transfer must not grant access to another account's records.
        if (customer == null || !SecurityFrameworkUtils.getLoginUserId().equals(customer.getOwnerUserId())) {
            throw TrialException.notFound();
        }
        return customer;
    }
    public List<CrmFollowUpRecordDO> followUps() {
        return followUps.getFollowUpRecordByBiz(CrmBizTypeEnum.CRM_CUSTOMER.getType(), List.of(customer().getId()));
    }
    public Long followUp(TrialFollowUpReqVO request) {
        var app = access.requireCurrent();
        return store.locked(app.id(), current -> {
            access.requireCurrent(); // Recheck expiry after lock acquisition.
            String hash = TrialServiceAuth.sha256(store.encode(List.of(request.getContent(), request.getType(), request.getNextTime().toString())));
            List<Map<String, Object>> previous = jdbc.queryForList("SELECT request_hash,follow_up_id FROM crm_trial_business_operation WHERE application_id=? AND idempotency_key=?",
                    app.id(), request.getIdempotencyKey());
            if (!previous.isEmpty()) {
                if (!hash.equals(previous.get(0).get("request_hash"))) { throw TrialException.conflict(); }
                return ((Number) previous.get(0).get("follow_up_id")).longValue();
            }
            dict.validateDictDataList("crm_follow_up_type", List.of(request.getType().toString()));
            var req = new CrmFollowUpRecordSaveReqVO();
            req.setBizId(customer(current).getId()); req.setBizType(CrmBizTypeEnum.CRM_CUSTOMER.getType());
            req.setContent(request.getContent()); req.setType(request.getType()); req.setNextTime(request.getNextTime());
            Long id = followUps.createFollowUpRecord(req);
            jdbc.update("INSERT INTO crm_trial_business_operation(application_id,idempotency_key,request_hash,follow_up_id,created_at) VALUES(?,?,?,?,?)",
                    app.id(), request.getIdempotencyKey(), hash, id, Timestamp.from(Instant.now()));
            return id;
        });
    }
}
