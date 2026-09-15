package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.dto.OAuth2AccessTokenCheckRespDTO;
import cn.iocoder.yudao.framework.common.biz.system.permission.PermissionCommonApi;
import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.security.core.filter.TokenAuthenticationFilter;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkServiceImpl;
import cn.iocoder.yudao.framework.tenant.core.web.TenantContextWebFilter;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.crm.controller.admin.trial.TrialSettingsController;
import cn.iocoder.yudao.module.crm.service.trial.settings.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import jakarta.servlet.Filter;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.*;
import javax.sql.DataSource;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringJUnitWebConfig(TrialSettingsHttpTest.Config.class)
@TestPropertySource(properties="mgs.trial.storage-enabled=true")
class TrialSettingsHttpTest {
    static final String BASE="/admin-api/crm/trial-settings";
    static final Path DIRECTORY=directory();
    static Path directory(){try{return Files.createTempDirectory("mgs-settings-test-");}catch(Exception e){throw new RuntimeException(e);}}
    @Resource WebApplicationContext context;
    @Resource JdbcTemplate jdbc;
    @Resource TrialProperties properties;
    @Resource TrialSettingsService service;
    @Resource TrialSettingsVault vault;
    @Resource TransactionTemplate transaction;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        properties.applyManaged(null);
        jdbc.update("UPDATE crm_trial_settings SET revision=0,ciphertext=NULL,nonce=NULL,operator_tenant_id=NULL");
        jdbc.update("DELETE FROM crm_trial_application");jdbc.update("DELETE FROM crm_trial_connector_operation");
        Files.deleteIfExists(DIRECTORY.resolve("master.key"));
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(new TenantContextWebFilter(),context.getBean("springSecurityFilterChain",Filter.class)).build();
    }
    @AfterEach void clear(){cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.clear();org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    @AfterAll static void cleanup() throws Exception {Files.deleteIfExists(DIRECTORY.resolve("master.key"));Files.deleteIfExists(DIRECTORY);}
    JsonNode call(MockHttpServletRequestBuilder request,String token,long tenant) throws Exception {
        var response=mvc.perform(request.header("Authorization","Bearer "+token).header("tenant-id",tenant)).andReturn().getResponse();
        assertEquals(200,response.getStatus());return json.readTree(response.getContentAsString());
    }
    JsonNode getState() throws Exception {return call(get(BASE+"/get"),"admin",121).path("data");}
    ObjectNode draft() throws Exception {
        ObjectNode d=(ObjectNode)getState().path("config").deepCopy();
        d.put("publicBaseUrl","https://mgs.example.invalid");d.put("smsEnabled",true);
        d.put("outboundSecret","test-outbound-secret-never-a-real-production-key");
        d.putArray("assistantIds").add("assistant-fixture");d.putArray("channels").add("web");return d;
    }
    JsonNode save(ObjectNode body) throws Exception{return call(put(BASE+"/save").contentType("application/json").content(json.writeValueAsBytes(body)),"admin",121);}
    JsonNode issue(String kind) throws Exception {return call(post(BASE+"/keys/issue").contentType("application/json").content(json.writeValueAsBytes(Map.of("revision",getState().path("revision").asLong(),"kind",kind,"days",90))),"admin",121);}
    @Test void encryptedPersistenceMaskedReadAndRestartRecovery() throws Exception {
        var d=draft();var saved=save(d);assertEquals(0,saved.path("code").asInt());assertEquals(1,saved.path("data").path("activeRevision").asLong());
        assertEquals("",saved.path("data").path("config").path("outboundSecret").asText());
        assertFalse(getState().toString().contains(d.path("outboundSecret").asText()));
        String ciphertext=jdbc.queryForObject("SELECT ciphertext FROM crm_trial_settings",String.class);
        assertFalse(ciphertext.contains("test-outbound"));assertFalse(ciphertext.contains("assistant-fixture"));
        assertEquals(32,Files.size(DIRECTORY.resolve("master.key")));
        var restarted=new TrialProperties();restarted.setOperatorTenantId(121L);restarted.setDemoTenantId(1L);
        var manager=new TrialSettingsService(restarted,jdbc,transaction,vault);manager.reload();
        assertEquals(d.path("outboundSecret").asText(),restarted.getOutboundSecret());assertTrue(restarted.getSmsVerification().ready());
        assertTrue(restarted.getLoginDelivery().ready());assertFalse(restarted.isEnabled());
    }
    @Test void lostUpdatesBadUrlsUnknownFieldsAndNestedAllowlistValidationCannotWrite() throws Exception {
        var old=draft();assertEquals(0,save(old).path("code").asInt());
        assertEquals(1_020_100_022,save(old).path("code").asInt());
        var bad=(ObjectNode)getState().path("config").deepCopy();bad.put("knowdoBaseUrl","http://invalid.example");
        assertNotEquals(0,save(bad).path("code").asInt());bad.put("knowdoBaseUrl","https://valid.example");
        bad.putArray("assistantIds").add("");assertNotEquals(0,save(bad).path("code").asInt());
        bad.putArray("assistantIds").add("fixture");bad.putArray("audiences").addNull();assertNotEquals(0,save(bad).path("code").asInt());
        bad.putArray("audiences").add("anonymous");bad.put("operatorTenantId",1);assertNotEquals(0,save(bad).path("code").asInt());
        assertEquals(1,jdbc.queryForObject("SELECT revision FROM crm_trial_settings",Long.class));
    }
    @Test void permissionsAndRealUserTenantBoundaryCannotBeBypassed() throws Exception {
        assertNotEquals(0,call(get(BASE+"/get"),"ordinary",121).path("code").asInt());
        assertEquals(1_020_100_021,call(get(BASE+"/get"),"foreign",1).path("code").asInt());
        assertEquals(1_020_100_021,call(get(BASE+"/get"),"foreign",121).path("code").asInt());
        assertEquals(0,call(get(BASE+"/get"),"readonly",121).path("code").asInt());
        assertNotEquals(0,call(put(BASE+"/save").contentType("application/json").content(json.writeValueAsBytes(draft())),"readonly",121).path("code").asInt());
        assertNotEquals(0,call(post(BASE+"/keys/issue").contentType("application/json").content("{\"revision\":0,\"kind\":\"TOOLS\",\"days\":90}"),"readonly",121).path("code").asInt());
        assertEquals(401,mvc.perform(get(BASE+"/get").header("Authorization","Bearer mgs_trial.fake.not-a-personal-login").header("tenant-id","121")).andReturn().getResponse().getStatus());
        assertEquals(0,jdbc.queryForObject("SELECT revision FROM crm_trial_settings",Long.class));
    }
    @Test void issueIsOneTimeExpiryAndRevocationAffectActualBearerAuth() throws Exception {
        assertEquals(0,save(draft()).path("code").asInt());var issued=issue("TOOLS");assertEquals(0,issued.path("code").asInt());
        String value=issued.path("data").path("value").asText();String keyId=issued.path("data").path("keyId").asText();
        assertTrue(value.startsWith("mgs_trial."));assertFalse(getState().toString().contains(value));
        issue("CARD");issue("PRIVATE_HMAC");
        var enabled=(ObjectNode)getState().path("config").deepCopy();enabled.put("enabled",true);enabled.put("connectorEnabled",true);
        assertEquals(0,save(enabled).path("code").asInt());
        var auth=new TrialConnectorAuth(properties,jdbc);var req=new org.springframework.mock.web.MockHttpServletRequest("POST","/admin-api/crm/trial-connector/status");req.setSecure(true);
        req.addHeader("Authorization","Bearer "+value);req.addHeader("X-KnowDo-Context",Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(Map.of("version",1,"actorId","actor","assistantId","assistant-fixture","audience","anonymous","channel","web","conversationId","conversation","taskId","task","operationId","operation"))));
        assertEquals("actor",auth.verify(req,"{}".getBytes(),"TOOLS").subjectId());
        var result=call(post(BASE+"/keys/revoke").contentType("application/json").content(json.writeValueAsBytes(Map.of("revision",getState().path("revision").asLong(),"keyId",keyId,"kind","TOOLS"))),"admin",121);
        assertEquals(0,result.path("code").asInt());assertThrows(RuntimeException.class,()->auth.verify(req,"{}".getBytes(),"TOOLS"));
    }
    @Test void enablingRequiresCompleteReferencesSecretsAndSeparateServiceCapabilities() throws Exception {
        var d=draft();d.put("enabled",true);d.put("connectorEnabled",true);assertNotEquals(0,save(d).path("code").asInt());assertFalse(properties.isEnabled());
        d.put("enabled",false);d.put("connectorEnabled",false);assertEquals(0,save(d).path("code").asInt());
        for(String kind:List.of("TOOLS","CARD","PRIVATE_HMAC"))assertEquals(0,issue(kind).path("code").asInt());
        d=(ObjectNode)getState().path("config").deepCopy();d.put("enabled",true);d.put("connectorEnabled",true);
        var result=save(d);assertEquals(0,result.path("code").asInt());assertTrue(result.path("data").path("localReady").asBoolean());
        assertEquals(110,properties.newPolicy().ownerUserId());assertTrue(properties.getConnector().isEnabled());
    }
    @Test void missingMasterKeyNeverRegeneratesAndDisablesNewRequests() throws Exception {
        save(draft());String before=jdbc.queryForObject("SELECT ciphertext FROM crm_trial_settings",String.class);
        Files.delete(DIRECTORY.resolve("master.key"));service.reload();assertFalse(properties.isEnabled());assertFalse(properties.getConnector().isEnabled());
        assertNotEquals(0,call(get(BASE+"/get"),"admin",121).path("code").asInt());
        assertFalse(Files.exists(DIRECTORY.resolve("master.key")));assertEquals(before,jdbc.queryForObject("SELECT ciphertext FROM crm_trial_settings",String.class));
    }
    @Test void secretValidationDoesNotEchoValueAndBlankSecretPreservesExistingKey() throws Exception {
        save(draft());String secret=properties.getOutboundSecret();var d=(ObjectNode)getState().path("config").deepCopy();
        assertEquals(0,save(d).path("code").asInt());assertEquals(secret,properties.getOutboundSecret());
        d=(ObjectNode)getState().path("config").deepCopy();String sensitive="sensitive-test-value-".repeat(30);d.put("outboundSecret",sensitive);
        var response=mvc.perform(put(BASE+"/save").header("Authorization","Bearer admin").header("tenant-id","121").contentType("application/json").content(json.writeValueAsBytes(d))).andReturn().getResponse();
        assertFalse(response.getContentAsString().contains(sensitive));assertTrue(response.getHeader("Cache-Control").contains("no-store"));
        assertNotEquals(0,json.readTree(response.getContentAsString()).path("code").asInt());
    }
    @Test void activeApplicationsProtectCallbackCredentialsAndDatabaseFailuresDoNotLeakSecrets() throws Exception {
        save(draft());
        jdbc.update("INSERT INTO crm_trial_application VALUES('fixture-active','SUBMITTED')");
        var d=(ObjectNode)getState().path("config").deepCopy();d.put("outboundKeyId","replacement");
        assertNotEquals(0,save(d).path("code").asInt());
        d.put("outboundKeyId",properties.getOutboundKeyId());d.put("outboundSecret","replacement-secret-only-used-in-isolated-test");
        assertNotEquals(0,save(d).path("code").asInt());
        assertEquals(1,jdbc.queryForObject("SELECT revision FROM crm_trial_settings",Long.class));
        jdbc.execute("ALTER TABLE crm_trial_application RENAME TO crm_trial_application_offline");
        try {
            var result=save(d);
            assertEquals(1_020_100_020,result.path("code").asInt());
            assertFalse(result.toString().contains("replacement-secret"));
            assertEquals(1,jdbc.queryForObject("SELECT revision FROM crm_trial_settings",Long.class));
        } finally {jdbc.execute("ALTER TABLE crm_trial_application_offline RENAME TO crm_trial_application");}
    }
    @Configuration @EnableWebMvc @EnableWebSecurity @EnableMethodSecurity
    @Import({TrialSettingsService.class,TrialSettingsController.class})
    static class Config implements WebMvcConfigurer {
        @Bean TrialProperties properties(){var p=new TrialProperties();p.setOperatorTenantId(121L);p.setDemoTenantId(1L);p.setOwnerUserId(110L);p.setEnvironment("fixture");p.setDurationDays(7);p.setMaxApplications(100);p.setOauthClientId("fixture");p.setOutboundKeyId("fixture");p.setKnowdoBaseUrl("https://knowdo.example.invalid");p.setMgsLoginUrl("https://mgs.example.invalid");p.getSmsVerification().setTemplateCode("trial-mobile-verify");return p;}
        @Bean TrialSettingsVault vault(){return new TrialSettingsVault(DIRECTORY.resolve("master.key").toString());}
        @Bean DataSource dataSource(){var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:settings-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
            new ResourceDatabasePopulator(new FileSystemResource("../script/trial/V20260915_08__trial_settings.sql"),new FileSystemResource("../script/trial/V20260915_07__trial_connector_operation.sql")).execute(ds);
            var j=new JdbcTemplate(ds);
            j.execute("CREATE TABLE system_users(id BIGINT,tenant_id BIGINT,status INT,deleted INT)");j.update("INSERT INTO system_users VALUES(110,121,0,0),(111,121,0,0),(112,121,0,0),(120,1,0,0)");
            j.execute("CREATE TABLE system_tenant(id BIGINT,status INT,deleted INT)");j.update("INSERT INTO system_tenant VALUES(121,0,0),(1,0,0)");
            j.execute("CREATE TABLE system_oauth2_client(client_id VARCHAR(64),status INT,deleted INT)");j.update("INSERT INTO system_oauth2_client VALUES('fixture',0,0)");
            j.execute("CREATE TABLE system_sms_channel(id BIGINT,status INT,deleted INT)");j.update("INSERT INTO system_sms_channel VALUES(1,0,0)");
            j.execute("CREATE TABLE system_sms_template(code VARCHAR(64),channel_id BIGINT,status INT,deleted INT)");j.update("INSERT INTO system_sms_template VALUES('trial-mobile-verify',1,0,0)");
            j.execute("CREATE TABLE crm_trial_application(id VARCHAR(36),status VARCHAR(32))");return ds;}
        @Bean JdbcTemplate jdbc(DataSource ds){return new JdbcTemplate(ds);}
        @Bean TransactionTemplate transaction(DataSource ds){return new TransactionTemplate(new DataSourceTransactionManager(ds));}
        @Bean PermissionCommonApi permissions(){var p=mock(PermissionCommonApi.class);when(p.hasAnyPermissions(eq(110L),any(String[].class))).thenReturn(true);when(p.hasAnyPermissions(eq(120L),any(String[].class))).thenReturn(true);when(p.hasAnyPermissions(111L,"crm:trial-settings:query")).thenReturn(true);return p;}
        @Bean(name="ss") SecurityFrameworkService ss(PermissionCommonApi permissions){return new SecurityFrameworkServiceImpl(permissions);}
        @Bean GlobalExceptionHandler errors(){return new GlobalExceptionHandler("settings-test",mock(ApiErrorLogCommonApi.class));}
        @Bean OAuth2TokenCommonApi tokens(){var api=mock(OAuth2TokenCommonApi.class);Map.of("admin",110L,"readonly",111L,"ordinary",112L,"foreign",120L).forEach((token,id)->when(api.checkAccessToken(token)).thenReturn(new OAuth2AccessTokenCheckRespDTO().setUserId(id).setUserType(2).setTenantId(id==120?1L:121L).setScopes(List.of()).setExpiresTime(LocalDateTime.now().plusDays(1))));return api;}
        @Bean SecurityFilterChain security(HttpSecurity http,GlobalExceptionHandler errors,OAuth2TokenCommonApi tokens)throws Exception {
            new cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils(new WebProperties());
            return http.csrf(c->c.disable()).authorizeHttpRequests(c->c.anyRequest().authenticated()).exceptionHandling(c->c.authenticationEntryPoint((req,res,e)->res.setStatus(401)))
                    .addFilterBefore(new TokenAuthenticationFilter(new SecurityProperties(),errors,tokens),UsernamePasswordAuthenticationFilter.class).build();}
        @Override public void configurePathMatch(PathMatchConfigurer c){c.addPathPrefix("/admin-api",t->t==TrialSettingsController.class);}
    }
}
