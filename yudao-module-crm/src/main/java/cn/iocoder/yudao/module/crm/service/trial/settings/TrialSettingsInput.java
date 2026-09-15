package cn.iocoder.yudao.module.crm.service.trial.settings;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.ToString;
import java.util.List;

@Data
public class TrialSettingsInput {
    @NotNull @Min(0) private Long revision;
    private boolean enabled;
    private boolean connectorEnabled;
    private boolean smsEnabled;
    @Size(max=64) private String environment;
    @Size(max=512) private String publicBaseUrl;
    @Size(max=512) private String mgsLoginUrl;
    @Size(max=512) private String knowdoBaseUrl;
    @Min(1) private Long ownerUserId;
    @Min(1) @Max(90) private Integer durationDays;
    @Min(1) @Max(100000) private Integer maxApplications;
    @Size(max=64) private String oauthClientId;
    @Size(max=64) private String outboundKeyId;
    @Size(max=512) @ToString.Exclude private String outboundSecret;
    @Size(max=64) private String smsTemplateCode;
    @Min(60) @Max(600) private int codeTtlSeconds = 300;
    @Min(60) @Max(1800) private int proofTtlSeconds = 600;
    @Min(60) @Max(3600) private int resendSeconds = 60;
    @Min(1) @Max(5) private int maxAttempts = 5;
    @Min(1) @Max(20) private int maxPerMobilePerDay = 5;
    @Min(1) @Max(20) private int maxPerIdentityPerDay = 5;
    @Min(1) @Max(100000) private int maxTotalPerDay = 500;
    @NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{1,64}") private String issuer = "knowdo-trial";
    @NotNull @Size(max=50) private List<@NotBlank @Pattern(regexp="[a-zA-Z0-9_.:@/-]{1,160}") String> assistantIds = List.of();
    @NotNull @Size(max=3) private List<@NotBlank @Pattern(regexp="anonymous|customer|employee") String> audiences = List.of("anonymous");
    @NotNull @Size(max=50) private List<@NotBlank @Pattern(regexp="[a-zA-Z0-9_.:@/-]{1,160}") String> channels = List.of();
    @JsonAnySetter public void unknown(String name, Object value) { throw new IllegalArgumentException("不支持的配置字段"); }
}
