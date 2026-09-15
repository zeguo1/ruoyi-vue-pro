package cn.iocoder.yudao.module.crm.controller.admin.trial;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import cn.iocoder.yudao.module.crm.service.trial.TrialException;
import cn.iocoder.yudao.module.crm.service.trial.TrialConnectorAuth;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

@ControllerAdvice
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
@RequiredArgsConstructor
public class TrialConnectorRequestAdvice extends RequestBodyAdviceAdapter {
    public static final String IDENTITY_ATTRIBUTE = TrialRequestAdvice.IDENTITY_ATTRIBUTE;
    private final TrialConnectorAuth auth;
    private final HttpServletRequest request;
    private final jakarta.servlet.http.HttpServletResponse response;
    @Override
    public boolean supports(MethodParameter parameter, Type target, Class<? extends HttpMessageConverter<?>> converter) {
        return parameter.hasMethodAnnotation(TrialConnectorCapability.class);
    }
    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage message, MethodParameter parameter, Type target,
                                           Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        byte[] bytes = message.getBody().readNBytes(16_385);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        if (bytes.length > 16_384) { throw TrialException.unauthorized(); }
        TrialConnectorCapability capability = parameter.getMethodAnnotation(TrialConnectorCapability.class);
        request.setAttribute(IDENTITY_ATTRIBUTE, auth.verify(request, bytes, capability.value()));
        return new HttpInputMessage() {
            @Override public InputStream getBody() { return new ByteArrayInputStream(bytes); }
            @Override public HttpHeaders getHeaders() { return message.getHeaders(); }
        };
    }
}
