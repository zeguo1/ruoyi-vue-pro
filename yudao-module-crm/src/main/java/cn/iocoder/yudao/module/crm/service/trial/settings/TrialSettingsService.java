package cn.iocoder.yudao.module.crm.service.trial.settings;

import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.crm.service.trial.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

@Service
@ConditionalOnProperty(prefix="mgs.trial", name="storage-enabled", havingValue="true")
public class TrialSettingsService {
    private final TrialProperties properties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final TrialSettingsVault vault;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Long operatorTenant;
    private final TrialProperties bootstrap;
    private volatile long activeRevision;
    private volatile boolean loadFailed;

    public TrialSettingsService(TrialProperties properties, JdbcTemplate jdbc, TransactionTemplate transaction, TrialSettingsVault vault) {
        this.properties=properties; this.jdbc=jdbc; this.transaction=transaction; this.vault=vault;
        this.operatorTenant=properties.getOperatorTenantId(); this.bootstrap=copy(properties, TrialProperties.class);
    }
    @Data public static class Document {
        private TrialProperties values;
        private String publicBaseUrl = "";
        private String issuer = "knowdo-trial";
        private List<String> assistantIds = List.of();
        private List<String> audiences = List.of("anonymous");
        private List<String> channels = List.of();
    }
    public record KeyView(String id, String kind, boolean enabled, String expiresAt, boolean expired) { }
    public record View(long revision, long activeRevision, Long operatorTenantId, Long demoTenantId,
                       TrialSettingsInput config, Map<String,Boolean> secretsConfigured, List<KeyView> keys,
                       List<String> missing, boolean localReady, String source, String notice) { }
    public record Issued(View settings, String keyId, String kind, String value) {
        @Override public String toString() { return "Issued[REDACTED]"; }
    }
    private record Stored(long revision, Document document) { }

    public void requireOperator() {
        var user=SecurityFrameworkUtils.getLoginUser();
        if (operatorTenant==null || user==null || !Objects.equals(user.getTenantId(),operatorTenant)
                || !Objects.equals(TenantContextHolder.getTenantId(),operatorTenant) || !Integer.valueOf(2).equals(user.getUserType())
                || count("SELECT COUNT(*) FROM system_users WHERE id=? AND tenant_id=? AND status=0 AND deleted=0",user.getId(),operatorTenant)!=1) {
            throw TrialException.error(21,"仅允许运营租户的授权管理员维护对接配置");
        }
    }
    public synchronized View get() { requireOperator(); Stored stored=read(); apply(stored); return view(stored); }
    public synchronized View save(TrialSettingsInput input) {
        requireOperator();
        Stored result=change(input.getRevision(), d -> {
            var p=d.getValues();
            if ((!p.getConnector().getKeys().isEmpty() || !p.getKeys().isEmpty()) && !d.getIssuer().equals(input.getIssuer()))
                throw invalid("已签发凭据后不能更改身份签发方，请保留 issuer");
            checkUrl(input.getPublicBaseUrl(),"MGS 对外地址",true); checkUrl(input.getMgsLoginUrl(),"MGS 登录地址",false);
            checkUrl(input.getKnowdoBaseUrl(),"知办回调地址",false);
            String secret=trim(input.getOutboundSecret());
            if (!secret.isEmpty() && secret.length()<32) throw invalid("出站签名密钥至少 32 个字符");
            if (((!secret.isEmpty() && !Objects.equals(secret,p.getOutboundSecret())) || !Objects.equals(trim(input.getOutboundKeyId()),trim(p.getOutboundKeyId()))) && count("SELECT COUNT(*) FROM crm_trial_application WHERE status<>'EXPIRED'")>0)
                throw invalid("仍有未结束申请，不能直接替换出站密钥或编号；请先完成或结束申请");
            p.setEnabled(input.isEnabled()); p.setEnvironment(trim(input.getEnvironment())); p.setOwnerUserId(input.getOwnerUserId());
            p.setDurationDays(input.getDurationDays());p.setMaxApplications(input.getMaxApplications());p.setOauthClientId(trim(input.getOauthClientId()));
            p.setMgsLoginUrl(trim(input.getMgsLoginUrl()));p.setKnowdoBaseUrl(trim(input.getKnowdoBaseUrl()));p.setOutboundKeyId(trim(input.getOutboundKeyId()));
            if (!secret.isEmpty()) p.setOutboundSecret(secret);
            d.setPublicBaseUrl(trim(input.getPublicBaseUrl()));d.setIssuer(input.getIssuer());
            d.setAssistantIds(List.copyOf(input.getAssistantIds()));d.setAudiences(List.copyOf(input.getAudiences()));d.setChannels(List.copyOf(input.getChannels()));
            p.getConnector().setEnabled(input.isConnectorEnabled());
            for (var key:p.getConnector().getKeys().values()) {
                key.setAssistantIds(Set.copyOf(d.getAssistantIds()));key.setAudiences(Set.copyOf(d.getAudiences()));key.setChannels(Set.copyOf(d.getChannels()));
            }
            var sms=p.getSmsVerification();sms.setEnabled(input.isSmsEnabled());sms.setTemplateCode(trim(input.getSmsTemplateCode()));
            sms.setCodeTtlSeconds(input.getCodeTtlSeconds());sms.setProofTtlSeconds(input.getProofTtlSeconds());sms.setResendSeconds(input.getResendSeconds());
            sms.setMaxAttempts(input.getMaxAttempts());sms.setMaxPerMobilePerDay(input.getMaxPerMobilePerDay());sms.setMaxPerIdentityPerDay(input.getMaxPerIdentityPerDay());sms.setMaxTotalPerDay(input.getMaxTotalPerDay());
            // Internal secrets never need to cross the browser. Preserve every existing vault key for old records.
            if (sms.getSecret()==null || sms.getSecret().isBlank()) sms.setSecret(random());
            if (!p.getLoginDelivery().ready()) {
                if (!p.getLoginDelivery().getEncryptionKeys().isEmpty()) throw invalid("原有凭据加密配置异常，请先修复，不能覆盖历史密钥");
                p.getLoginDelivery().setActiveKeyId("managed-v1");
                byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
                p.getLoginDelivery().setEncryptionKeys(Map.of("managed-v1",Base64.getEncoder().encodeToString(bytes)));
            }
            List<String> missing=missing(d);
            if ((p.isEnabled() || p.getConnector().isEnabled()) && !missing.isEmpty()) throw invalid("启用前请补齐："+String.join("；",missing));
        });
        return view(result);
    }
    public synchronized Issued issue(long revision, String kind, int days) {
        requireOperator();
        if (!Set.of("TOOLS","CARD","PRIVATE_HMAC").contains(kind) || days<1 || days>365) throw invalid("凭据类型或有效期无效");
        String id=kind.toLowerCase(Locale.ROOT)+"-"+UUID.randomUUID().toString().replace("-", "");
        String secret=random(), value="PRIVATE_HMAC".equals(kind)?secret:"mgs_trial."+id+"."+secret;
        Stored result=change(revision,d -> {
            var p=d.getValues();
            if (d.getAssistantIds().isEmpty() || d.getAudiences().isEmpty() || d.getChannels().isEmpty()) throw invalid("请先保存实际助手 ID、用户类型和渠道允许名单");
            Instant expires=Instant.now().plusSeconds(days*86400L);
            if ("PRIVATE_HMAC".equals(kind)) {
                var key=new TrialProperties.ServiceKey();key.setIssuer(d.getIssuer());key.setSecret(secret);key.setExpiresAt(expires);
                key.setCapabilities(Set.of("AUTHORIZATION","DELIVERY","EVENTS"));
                var keys=new HashMap<>(p.getKeys());keys.put(id,key);p.setKeys(keys);
            } else {
                var key=new TrialProperties.ConnectorKey();key.setIssuer(d.getIssuer());key.setEnabled(true);key.setExpiresAt(expires);
                key.setTokenSha256(TrialServiceAuth.sha256(value));key.setCapabilities(kind.equals("TOOLS")?Set.of("TOOLS"):Set.of("SMS_VERIFICATION","CONSENT"));
                key.setAssistantIds(Set.copyOf(d.getAssistantIds()));key.setAudiences(Set.copyOf(d.getAudiences()));key.setChannels(Set.copyOf(d.getChannels()));
                var keys=new HashMap<>(p.getConnector().getKeys());keys.put(id,key);p.getConnector().setKeys(keys);
            }
        });
        return new Issued(view(result),id,kind,value);
    }
    public synchronized View revoke(long revision,String id,String kind) {
        requireOperator();return view(change(revision,d -> {
            if ("PRIVATE_HMAC".equals(kind)) {
                var keys=new HashMap<>(d.getValues().getKeys());if(keys.remove(id)==null)throw invalid("凭据不存在");d.getValues().setKeys(keys);
            } else {
                var key=d.getValues().getConnector().getKeys().get(id);if(key==null)throw invalid("凭据不存在");key.setEnabled(false);
            }
        }));
    }
    private Stored change(long expected,Consumer<Document> update) {
        Stored result=transaction.execute(tx -> {
            var row=jdbc.queryForMap("SELECT * FROM crm_trial_settings WHERE id=1 FOR UPDATE");
            long revision=((Number)row.get("revision")).longValue();if(revision!=expected)throw TrialException.error(22,"配置已被其他管理员修改，请刷新后重试");
            Stored current=decode(row);Document next=copy(current.document(),Document.class);update.accept(next);
            byte[] bytes;try { bytes=json.writeValueAsBytes(next); } catch(Exception e){throw invalid("配置无法保存");}
            var encrypted=vault.encrypt(bytes,operatorTenant,revision+1,revision==0);
            jdbc.update("UPDATE crm_trial_settings SET revision=?,operator_tenant_id=?,nonce=?,ciphertext=?,updater=?,update_time=? WHERE id=1",
                    revision+1,operatorTenant,encrypted.nonce(),encrypted.ciphertext(),SecurityFrameworkUtils.getLoginUserId(),Timestamp.from(Instant.now()));
            return new Stored(revision+1,next);
        });
        apply(Objects.requireNonNull(result));return result;
    }
    private Stored read() {
        try{return decode(jdbc.queryForMap("SELECT * FROM crm_trial_settings WHERE id=1"));}
        catch(cn.iocoder.yudao.framework.common.exception.ServiceException e){throw e;}
        catch(Exception e){throw TrialException.error(20,"配置存储不可用，请检查配置表迁移和持久卷");}
    }
    private Stored decode(Map<String,Object> row) {
        long revision=((Number)row.get("revision")).longValue();
        if(revision==0) {
            var d=new Document();d.setValues(copy(bootstrap,TrialProperties.class));
            d.getValues().getConnector().getKeys().values().stream().findFirst().ifPresent(k -> {
                d.setIssuer(k.getIssuer());d.setAssistantIds(new ArrayList<>(k.getAssistantIds()));d.setAudiences(new ArrayList<>(k.getAudiences()));d.setChannels(new ArrayList<>(k.getChannels()));
            });return new Stored(0,d);
        }
        if(operatorTenant==null || ((Number)row.get("operator_tenant_id")).longValue()!=operatorTenant)throw invalid("配置所属运营租户与部署配置不一致");
        try {
            var d=json.readValue(vault.decrypt((String)row.get("nonce"),(String)row.get("ciphertext"),operatorTenant,revision),Document.class);
            if(!Objects.equals(d.getValues().getOperatorTenantId(),operatorTenant) || !Objects.equals(d.getValues().getDemoTenantId(),bootstrap.getDemoTenantId()))throw invalid("配置租户边界不一致");
            return new Stored(revision,d);
        } catch(cn.iocoder.yudao.framework.common.exception.ServiceException e){throw e;}
        catch(Exception e){throw invalid("配置解密或版本校验失败，请恢复有效配置");}
    }
    private void apply(Stored value) { properties.applyManaged(value.document().getValues());activeRevision=value.revision();loadFailed=false; }
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelay=5000,initialDelay=5000)
    public synchronized void reload() {
        try {var stored=read();if(loadFailed || stored.revision()!=activeRevision)apply(stored);}
        catch(RuntimeException e){loadFailed=true;var safe=copy(properties.snapshot(),TrialProperties.class);safe.setEnabled(false);safe.getConnector().setEnabled(false);properties.applyManaged(safe);}
    }
    private View view(Stored stored) {
        Document d=stored.document();var p=d.getValues();var s=p.getSmsVerification();var c=new TrialSettingsInput();
        c.setRevision(stored.revision());c.setEnabled(p.isEnabled());c.setConnectorEnabled(p.getConnector().isEnabled());c.setSmsEnabled(s.isEnabled());
        c.setEnvironment(p.getEnvironment());c.setPublicBaseUrl(d.getPublicBaseUrl());c.setMgsLoginUrl(p.getMgsLoginUrl());c.setKnowdoBaseUrl(p.getKnowdoBaseUrl());
        c.setOwnerUserId(p.getOwnerUserId());c.setDurationDays(p.getDurationDays());c.setMaxApplications(p.getMaxApplications());c.setOauthClientId(p.getOauthClientId());c.setOutboundKeyId(p.getOutboundKeyId());
        c.setOutboundSecret("");c.setSmsTemplateCode(s.getTemplateCode());c.setCodeTtlSeconds(s.getCodeTtlSeconds());c.setProofTtlSeconds(s.getProofTtlSeconds());c.setResendSeconds(s.getResendSeconds());
        c.setMaxAttempts(s.getMaxAttempts());c.setMaxPerMobilePerDay(s.getMaxPerMobilePerDay());c.setMaxPerIdentityPerDay(s.getMaxPerIdentityPerDay());c.setMaxTotalPerDay(s.getMaxTotalPerDay());
        c.setIssuer(d.getIssuer());c.setAssistantIds(d.getAssistantIds());c.setAudiences(d.getAudiences());c.setChannels(d.getChannels());
        var keys=new ArrayList<KeyView>();
        p.getConnector().getKeys().forEach((id,k) -> keys.add(new KeyView(id,k.getCapabilities().contains("TOOLS")?"TOOLS":"CARD",k.isEnabled(),iso(k.getExpiresAt()),expired(k.getExpiresAt()))));
        p.getKeys().forEach((id,k) -> keys.add(new KeyView(id,"PRIVATE_HMAC",true,iso(k.getExpiresAt()),k.getExpiresAt()!=null && expired(k.getExpiresAt()))));
        keys.sort(Comparator.comparing(KeyView::id));var missing=missing(d);
        return new View(stored.revision(),activeRevision,operatorTenant,bootstrap.getDemoTenantId(),c,
                Map.of("outbound",!trim(p.getOutboundSecret()).isEmpty(),"sms",!trim(s.getSecret()).isEmpty(),"loginEncryption",p.getLoginDelivery().ready()),
                keys,missing,missing.isEmpty(),stored.revision()==0?"部署配置":"后台配置",
                "保存后应用于本实例，其他实例最多约 5 秒刷新；已有申请的租户、期限和地址保持其申请快照。配置检查不代表 HTTPS、短信供应商或知办回调已经联调成功。");
    }
    private List<String> missing(Document d) {
        var result=new ArrayList<String>();var p=d.getValues();
        if(operatorTenant==null || p.getDemoTenantId()==null || Objects.equals(operatorTenant,p.getDemoTenantId())
                || count("SELECT COUNT(*) FROM system_tenant WHERE id=? AND status=0 AND deleted=0",operatorTenant)!=1
                || count("SELECT COUNT(*) FROM system_tenant WHERE id=? AND status=0 AND deleted=0",p.getDemoTenantId())!=1)result.add("部署配置中的运营租户和演示租户必须有效且不同");
        if(trim(p.getEnvironment()).isEmpty())result.add("环境标识");
        if(trim(d.getPublicBaseUrl()).isEmpty())result.add("MGS 对外 HTTPS 地址");
        if(trim(p.getMgsLoginUrl()).isEmpty())result.add("MGS 登录 HTTPS 地址");
        if(trim(p.getKnowdoBaseUrl()).isEmpty())result.add("知办回调 HTTPS 地址");
        if(p.getDurationDays()==null)result.add("试用期限");if(p.getMaxApplications()==null)result.add("体验容量");
        if(p.getOwnerUserId()==null || count("SELECT COUNT(*) FROM system_users WHERE id=? AND tenant_id=? AND status=0 AND deleted=0",p.getOwnerUserId(),operatorTenant)!=1)result.add("运营租户内有效的线索负责人");
        if(trim(p.getOauthClientId()).isEmpty() || count("SELECT COUNT(*) FROM system_oauth2_client WHERE client_id=? AND status=0 AND deleted=0",p.getOauthClientId())!=1)result.add("已启用的 OAuth 客户端");
        if(trim(p.getOutboundKeyId()).isEmpty() || trim(p.getOutboundSecret()).length()<32)result.add("知办回调签名编号及密钥");
        var sms=p.getSmsVerification();
        if(!sms.ready())result.add("启用手机号验证并初始化内部密钥");
        if(trim(sms.getTemplateCode()).isEmpty() || count("SELECT COUNT(*) FROM system_sms_template t JOIN system_sms_channel c ON c.id=t.channel_id WHERE t.code=? AND t.status=0 AND t.deleted=0 AND c.status=0 AND c.deleted=0",sms.getTemplateCode())!=1)result.add("已启用的短信模板及通道");
        if(!p.getLoginDelivery().ready())result.add("内部登录凭据加密密钥（保存时自动初始化）");
        if(d.getAssistantIds().isEmpty() || d.getAudiences().isEmpty() || d.getChannels().isEmpty())result.add("实际助手 ID、用户类型和渠道允许名单");
        for(String capability:List.of("TOOLS","SMS_VERIFICATION","CONSENT")) {
            if(p.getConnector().getKeys().values().stream().noneMatch(k -> k.isEnabled() && !expired(k.getExpiresAt()) && k.getIssuer().equals(d.getIssuer()) && k.getCapabilities().contains(capability)))result.add("有效服务凭据："+capability);
        }
        for(String capability:List.of("AUTHORIZATION","DELIVERY","EVENTS")) {
            if(p.getKeys().values().stream().noneMatch(k -> (k.getExpiresAt()==null || !expired(k.getExpiresAt())) && k.getIssuer().equals(d.getIssuer()) && k.getCapabilities().contains(capability)))result.add("知办私有 HMAC 凭据："+capability);
        }
        return result;
    }
    private int count(String sql,Object...args){Integer n=jdbc.queryForObject(sql,Integer.class,args);return n==null?0:n;}
    private <T>T copy(Object value,Class<T> type){return json.convertValue(value,type);}
    private static String random(){byte[] value=new byte[32];new SecureRandom().nextBytes(value);return Base64.getUrlEncoder().withoutPadding().encodeToString(value);}
    private static String trim(String value){return value==null?"":value.trim();}
    private static String iso(Instant value){return value==null?"":value.toString();}
    private static boolean expired(Instant value){return value==null || !value.isAfter(Instant.now());}
    private static void checkUrl(String value,String name,boolean origin) {
        if(trim(value).isEmpty())return;
        try {var u=URI.create(value);if(!"https".equals(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null || u.getRawQuery()!=null || u.getRawFragment()!=null || (origin && !Set.of("","/").contains(u.getPath())))throw new IllegalArgumentException();}
        catch(Exception e){throw invalid(name+"必须是无账号、查询参数和片段的 HTTPS 地址"+(origin?"，不包含接口路径":""));}
    }
    private static RuntimeException invalid(String message){return TrialException.error(23,message);}
}
