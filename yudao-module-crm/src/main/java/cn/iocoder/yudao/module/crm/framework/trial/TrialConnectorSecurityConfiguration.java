package cn.iocoder.yudao.module.crm.framework.trial;

import cn.iocoder.yudao.framework.web.config.WebProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import java.util.stream.Stream;

/** The service bearer must never be interpreted as a personal OAuth token or establish a LoginUser. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "mgs.trial", name = "storage-enabled", havingValue = "true")
public class TrialConnectorSecurityConfiguration {
    @Bean @Order(-10)
    public SecurityFilterChain trialConnectorSecurity(HttpSecurity http, WebProperties web) throws Exception {
        String prefix = web.getAdminApi().getPrefix() + "/crm/";
        var routes = Stream.of("trial-connector/submit", "trial-connector/create-accounts", "trial-connector/status",
                "trial-connector/guide", "trial-connector-private/sms/send", "trial-connector-private/sms/verify",
                "trial-connector-private/confirm")
                .map(path -> new AntPathRequestMatcher(prefix + path, HttpMethod.POST.name())).toList();
        // Every matched controller method authenticates the raw request in TrialConnectorRequestAdvice.
        // Exact route + method matching leaves all ordinary APIs on the existing user-authentication chain.
        return http.securityMatcher(new OrRequestMatcher(routes.toArray(AntPathRequestMatcher[]::new)))
                .csrf(c -> c.disable()).sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(c -> c.anyRequest().permitAll()).build();
    }
}
