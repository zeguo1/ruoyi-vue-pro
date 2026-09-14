package cn.iocoder.yudao.module.crm.service.trial;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Set;

/** Internal one-time setup. None of these settings are accepted as Agent tool arguments. */
@Data
@Component
@ConfigurationProperties("mgs.trial.operator-bootstrap")
public class TrialOperatorBootstrapProperties {
    private boolean enabled;
    private String contactName;
    private String username;
    @ToString.Exclude
    private String password;
    private LocalDateTime expireTime;
    private Integer accountCount;
    private Set<Long> menuIds = Set.of();

    public void validate() {
        if (!enabled || contactName == null || contactName.isBlank() || contactName.length() > 50
                || username == null || !username.matches("[a-zA-Z0-9]{4,30}")
                || password == null || password.length() < 12 || password.length() > 16
                || expireTime == null || !expireTime.isAfter(LocalDateTime.now())
                || accountCount == null || accountCount < 1
                || menuIds == null || menuIds.isEmpty() || menuIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw TrialException.unavailable();
        }
    }
}
