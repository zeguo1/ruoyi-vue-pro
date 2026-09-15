package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.common.core.KeyValue;
import cn.iocoder.yudao.module.system.service.sms.SmsChannelService;
import cn.iocoder.yudao.module.system.service.sms.SmsTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/** Reuses configured channels/SDKs, bypassing the generic plaintext SMS-log/MQ pipeline for secret codes. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
public class MgsTrialSmsSender implements TrialSmsSender {
    private final SmsTemplateService templates;
    private final SmsChannelService channels;

    @Override public void send(String mobile, String code, String templateCode) {
        try {
            var template = templates.getSmsTemplateByCodeFromCache(templateCode);
            if (template == null || !Integer.valueOf(0).equals(template.getStatus())
                    || !List.of("code").equals(template.getParams())) { throw TrialException.unavailable(); }
            var channel = channels.getSmsChannel(template.getChannelId());
            if (channel == null || !Integer.valueOf(0).equals(channel.getStatus())
                    || !java.util.Set.of("ALIYUN", "TENCENT", "HUAWEI", "QINIU").contains(channel.getCode())) {
                throw TrialException.unavailable();
            }
            var client = channels.getSmsClient(channel.getId());
            var result = client.sendSms(cn.hutool.core.util.IdUtil.getSnowflakeNextId(), mobile,
                    template.getApiTemplateId(), List.of(new KeyValue<>("code", code)));
            if (result == null || !Boolean.TRUE.equals(result.getSuccess())) { throw TrialException.error(17, "短信暂未受理，请稍后重新获取"); }
        } catch (Throwable ignored) {
            // Provider exceptions can contain the entire signed payload/code. Never retain or log the cause.
            throw TrialException.error(17, "短信发送结果未确认，请稍后重新获取");
        }
    }
}
