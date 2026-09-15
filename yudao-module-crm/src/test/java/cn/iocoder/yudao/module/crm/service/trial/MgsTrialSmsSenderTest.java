package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.system.dal.dataobject.sms.SmsChannelDO;
import cn.iocoder.yudao.module.system.dal.dataobject.sms.SmsTemplateDO;
import cn.iocoder.yudao.module.system.framework.sms.core.client.SmsClient;
import cn.iocoder.yudao.module.system.framework.sms.core.client.dto.SmsSendRespDTO;
import cn.iocoder.yudao.module.system.service.sms.SmsChannelService;
import cn.iocoder.yudao.module.system.service.sms.SmsTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MgsTrialSmsSenderTest {
    SmsTemplateService templates = mock(SmsTemplateService.class);
    SmsChannelService channels = mock(SmsChannelService.class);
    SmsClient client = mock(SmsClient.class);
    MgsTrialSmsSender sender = new MgsTrialSmsSender(templates, channels);
    SmsTemplateDO template = new SmsTemplateDO();
    SmsChannelDO channel = new SmsChannelDO();
    @BeforeEach void setup() {
        template.setStatus(0); template.setChannelId(11L); template.setApiTemplateId("fixture-template"); template.setParams(List.of("code"));
        channel.setId(11L); channel.setStatus(0); channel.setCode("ALIYUN");
        when(templates.getSmsTemplateByCodeFromCache("fixture")).thenReturn(template);
        when(channels.getSmsChannel(11L)).thenReturn(channel); when(channels.getSmsClient(11L)).thenReturn(client);
    }
    @Test void enabledRealChannelUsesExistingClientWithoutGenericSmsLogPipeline() throws Throwable {
        when(client.sendSms(anyLong(), anyString(), anyString(), anyList())).thenReturn(new SmsSendRespDTO().setSuccess(true));
        sender.send("13800000001", "123456", "fixture");
        verify(client).sendSms(anyLong(), eq("13800000001"), eq("fixture-template"), argThat(params ->
                params.size() == 1 && "code".equals(params.get(0).getKey()) && "123456".equals(params.get(0).getValue())));
    }
    @Test void disabledAndDebugChannelsCannotProduceFalseVerification() {
        channel.setStatus(1); assertThrows(ServiceException.class, () -> sender.send("13800000001", "123456", "fixture"));
        channel.setStatus(0); channel.setCode("DEBUG_DING_TALK");
        assertThrows(ServiceException.class, () -> sender.send("13800000001", "123456", "fixture"));
        verifyNoInteractions(client);
    }
    @Test void unsupportedTemplateAndProviderErrorsNeverExposeCode() throws Throwable {
        template.setParams(List.of("code", "unknown"));
        assertThrows(ServiceException.class, () -> sender.send("13800000001", "123456", "fixture")); verifyNoInteractions(client);
        template.setParams(List.of("code"));
        when(client.sendSms(anyLong(), anyString(), anyString(), anyList())).thenThrow(new IllegalStateException("provider echoed 123456"));
        var error = assertThrows(ServiceException.class, () -> sender.send("13800000001", "123456", "fixture"));
        assertFalse(error.getMessage().contains("123456")); assertNull(error.getCause());
    }
    @Test void actualClientRefreshLoggerDoesNotExposeProviderCredentials() {
        var first = new cn.iocoder.yudao.module.system.framework.sms.core.property.SmsChannelProperties(); first.setId(11L);
        var second = new cn.iocoder.yudao.module.system.framework.sms.core.property.SmsChannelProperties(); second.setId(12L);
        second.setApiKey("fixture-private-key"); second.setApiSecret("fixture-private-secret"); second.setCallbackUrl("https://example.invalid/?token=fixture-private-callback");
        var client = mock(cn.iocoder.yudao.module.system.framework.sms.core.client.impl.AbstractSmsClient.class,
                withSettings().useConstructor(first).defaultAnswer(CALLS_REAL_METHODS));
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(cn.iocoder.yudao.module.system.framework.sms.core.client.impl.AbstractSmsClient.class);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(); events.start(); logger.addAppender(events);
        try {
            client.refresh(second);
            assertFalse(events.list.isEmpty());
            assertTrue(events.list.stream().noneMatch(event -> event.getFormattedMessage().contains("fixture-private")));
            assertEquals("fixture-private-secret", second.getApiSecret()); // Credential use is unchanged.
        } finally { logger.detachAppender(events); events.stop(); }
    }
}
