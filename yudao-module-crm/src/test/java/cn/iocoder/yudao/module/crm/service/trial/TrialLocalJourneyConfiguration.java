package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.permission.PermissionCommonApi;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.security.core.filter.TokenAuthenticationFilter;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.security.TenantSecurityWebFilter;
import cn.iocoder.yudao.framework.tenant.core.service.TenantFrameworkService;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.crm.controller.admin.trial.TrialBusinessController;
import cn.iocoder.yudao.module.crm.dal.mysql.clue.CrmClueMapper;
import cn.iocoder.yudao.module.crm.service.clue.*;
import cn.iocoder.yudao.module.infra.api.config.ConfigApi;
import cn.iocoder.yudao.module.system.api.oauth2.OAuth2TokenApiImpl;
import cn.iocoder.yudao.module.system.api.permission.PermissionApiImpl;
import cn.iocoder.yudao.module.system.api.user.*;
import cn.iocoder.yudao.module.system.api.sms.SmsCodeApi;
import cn.iocoder.yudao.module.system.controller.admin.auth.AuthController;
import cn.iocoder.yudao.module.system.dal.mysql.dept.UserPostMapper;
import cn.iocoder.yudao.module.system.dal.mysql.permission.*;
import cn.iocoder.yudao.module.system.dal.mysql.tenant.*;
import cn.iocoder.yudao.module.system.dal.mysql.user.AdminUserMapper;
import cn.iocoder.yudao.module.system.dal.mysql.oauth2.*;
import cn.iocoder.yudao.module.system.dal.mysql.logger.LoginLogMapper;
import cn.iocoder.yudao.module.system.dal.redis.oauth2.OAuth2AccessTokenRedisDAO;
import cn.iocoder.yudao.module.system.mq.producer.user.AdminUserProducer;
import cn.iocoder.yudao.module.system.service.auth.AdminAuthServiceImpl;
import cn.iocoder.yudao.module.system.service.dept.*;
import cn.iocoder.yudao.module.system.service.logger.LoginLogServiceImpl;
import cn.iocoder.yudao.module.system.service.member.MemberService;
import cn.iocoder.yudao.module.system.service.oauth2.*;
import cn.iocoder.yudao.module.system.service.permission.*;
import cn.iocoder.yudao.module.system.service.social.*;
import cn.iocoder.yudao.module.system.service.tenant.*;
import cn.iocoder.yudao.module.system.service.user.AdminUserServiceImpl;
import com.anji.captcha.service.CaptchaService;
import com.github.fppt.jedismock.RedisServer;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;

import javax.sql.DataSource;
import java.net.InetAddress;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;

/** Real MGS services/HTTP filters; only KnowDo and peripheral services are fixture boundaries.
 * Token persistence uses real Redis DAO with a loopback, ephemeral-port jedis-mock server, never shared Redis. */
@Configuration
@org.springframework.cache.annotation.EnableCaching
@org.springframework.web.servlet.config.annotation.EnableWebMvc
@org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
@EnableAspectJAutoProxy(proxyTargetClass = true)
@org.springframework.transaction.annotation.EnableTransactionManagement
@Import({AdminUserServiceImpl.class, RoleServiceImpl.class, PermissionServiceImpl.class, MenuServiceImpl.class,
        TenantServiceImpl.class, TenantPackageServiceImpl.class, OAuth2TokenServiceImpl.class, OAuth2ClientServiceImpl.class,
        OAuth2AccessTokenRedisDAO.class, AdminAuthServiceImpl.class, LoginLogServiceImpl.class, AuthController.class,
        MgsTrialProvisioner.class, TrialLoginVault.class, TrialLoginDeliveryService.class, TrialAuthorizationService.class,
        MgsTrialOAuthGateway.class, TrialOrchestrator.class, TrialEventService.class})
class TrialLocalJourneyConfiguration extends TrialBusinessIntegrationConfiguration {
    @Bean @Override DataSource dataSource() throws Exception {
        var ds = new MgsTrialAccountIntegrationTest.Config().dataSource();
        // Existing system unit-test DDL omits the production login-log tenant column.
        new org.springframework.jdbc.core.JdbcTemplate(ds).execute("ALTER TABLE system_login_log ADD tenant_id BIGINT NOT NULL DEFAULT 0");
        // Production SQL allows null scopes for browser-login grants; old system unit fixtures do not.
        for (String table : List.of("system_oauth2_access_token", "system_oauth2_refresh_token")) {
            new org.springframework.jdbc.core.JdbcTemplate(ds).execute("ALTER TABLE " + table + " ALTER COLUMN scopes DROP NOT NULL");
        }
        createCrmTables(ds, Set.of("crm_customer", "crm_follow_up_record", "crm_permission", "crm_clue"));
        return ds;
    }
    @Bean @Override SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
        var factory = super.sqlSessionFactory(ds);
        for (var mapper : List.of(AdminUserMapper.class, RoleMapper.class, RoleMenuMapper.class, UserRoleMapper.class,
                UserPostMapper.class, MenuMapper.class, TenantMapper.class, TenantPackageMapper.class, OAuth2ClientMapper.class,
                OAuth2AccessTokenMapper.class, OAuth2RefreshTokenMapper.class, LoginLogMapper.class, CrmClueMapper.class)) {
            factory.getConfiguration().addMapper(mapper);
        }
        return factory;
    }
    @Bean AdminUserMapper userMapper(SqlSessionTemplate t) { return t.getMapper(AdminUserMapper.class); }
    @Bean RoleMapper roleMapper(SqlSessionTemplate t) { return t.getMapper(RoleMapper.class); }
    @Bean RoleMenuMapper roleMenuMapper(SqlSessionTemplate t) { return t.getMapper(RoleMenuMapper.class); }
    @Bean UserRoleMapper userRoleMapper(SqlSessionTemplate t) { return t.getMapper(UserRoleMapper.class); }
    @Bean UserPostMapper userPostMapper(SqlSessionTemplate t) { return t.getMapper(UserPostMapper.class); }
    @Bean MenuMapper menuMapper(SqlSessionTemplate t) { return t.getMapper(MenuMapper.class); }
    @Bean TenantMapper tenantMapper(SqlSessionTemplate t) { return t.getMapper(TenantMapper.class); }
    @Bean TenantPackageMapper tenantPackageMapper(SqlSessionTemplate t) { return t.getMapper(TenantPackageMapper.class); }
    @Bean OAuth2ClientMapper clientMapper(SqlSessionTemplate t) { return t.getMapper(OAuth2ClientMapper.class); }
    @Bean OAuth2AccessTokenMapper accessTokenMapper(SqlSessionTemplate t) { return t.getMapper(OAuth2AccessTokenMapper.class); }
    @Bean OAuth2RefreshTokenMapper refreshTokenMapper(SqlSessionTemplate t) { return t.getMapper(OAuth2RefreshTokenMapper.class); }
    @Bean LoginLogMapper loginLogMapper(SqlSessionTemplate t) { return t.getMapper(LoginLogMapper.class); }
    @Bean CrmClueMapper clueMapper(SqlSessionTemplate t) { return t.getMapper(CrmClueMapper.class); }
    @Bean @Override OAuth2TokenCommonApi tokens() { return new OAuth2TokenApiImpl(); }
    @Bean @Override PermissionCommonApi permissions() { return new PermissionApiImpl(); }
    @Bean @Override AdminUserApi adminUserApi() { return new AdminUserApiImpl(); }
    @Bean @Override CrmClueService clueService() { return new CrmClueServiceImpl(); }
    @Bean TrialProperties trialProperties() { return new MgsTrialAccountIntegrationTest.Config().trialProperties(); }
    @Bean org.springframework.cache.CacheManager cacheManager() { return new org.springframework.cache.concurrent.ConcurrentMapCacheManager(); }
    @Bean TenantProperties tenantProperties() { var p = new TenantProperties(); p.setEnable(true); return p; }
    @Bean org.springframework.validation.beanvalidation.LocalValidatorFactoryBean validator() { return new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean(); }
    @Bean SecurityProperties securityProperties() { return new SecurityProperties(); }
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(4); }
    @Bean DeptService deptService() { return mock(DeptService.class); }
    @Bean PostService postService() { return mock(PostService.class); }
    @Bean ConfigApi configApi() { return mock(ConfigApi.class); }
    @Bean AdminUserProducer adminUserProducer() { return mock(AdminUserProducer.class); }
    @Bean SocialUserService socialUserService() { return mock(SocialUserService.class); }
    @Bean SocialClientService socialClientService() { return mock(SocialClientService.class); }
    @Bean MemberService memberService() { return mock(MemberService.class); }
    @Bean CaptchaService captchaService() { return mock(CaptchaService.class); }
    @Bean SmsCodeApi smsCodeApi() { return mock(SmsCodeApi.class); }
    @Bean KnowdoTrialAdapter knowdo() { return mock(KnowdoTrialAdapter.class); }
    @Bean(destroyMethod = "stop") RedisServer redisServer() throws Exception {
        return new RedisServer(0, InetAddress.getLoopbackAddress()).start();
    }
    @Bean(destroyMethod = "shutdown") RedissonClient redissonClient(RedisServer server) {
        var config = new org.redisson.config.Config(); config.setThreads(2); config.setNettyThreads(2);
        config.useSingleServer().setAddress("redis://127.0.0.1:" + server.getBindPort())
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(4);
        return Redisson.create(config);
    }
    @Bean RedissonConnectionFactory redisConnectionFactory(RedissonClient client) { return new RedissonConnectionFactory(client); }
    @Bean StringRedisTemplate stringRedisTemplate(RedissonConnectionFactory factory) { return new StringRedisTemplate(factory); }
    @Bean @Override SecurityFilterChain security(HttpSecurity http, GlobalExceptionHandler errors, OAuth2TokenCommonApi tokens) throws Exception {
        var web = new WebProperties(); new WebFrameworkUtils(web);
        var tokenFilter = new TokenAuthenticationFilter(new SecurityProperties(), errors, tokens);
        var tenantFilter = new TenantSecurityWebFilter(web, new TenantProperties(), Set.of(), errors, mock(TenantFrameworkService.class));
        return http.csrf(c -> c.disable()).sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(c -> c.requestMatchers("/admin-api/system/auth/login").permitAll().anyRequest().authenticated())
                .exceptionHandling(c -> c.authenticationEntryPoint((req, res, ex) -> res.setStatus(401)))
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(tenantFilter, TokenAuthenticationFilter.class).build();
    }
    @Override public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/admin-api", type -> type == TrialBusinessController.class || type == AuthController.class);
    }
}
