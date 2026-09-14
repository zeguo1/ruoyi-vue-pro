package cn.iocoder.yudao.module.crm.service.trial;

import cn.hutool.extra.spring.SpringUtil;
import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.permission.PermissionCommonApi;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.security.core.filter.TokenAuthenticationFilter;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkServiceImpl;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.framework.tenant.core.security.TenantSecurityWebFilter;
import cn.iocoder.yudao.framework.tenant.core.service.TenantFrameworkService;
import cn.iocoder.yudao.framework.tenant.core.web.TenantVisitContextInterceptor;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.crm.controller.admin.trial.TrialBusinessController;
import cn.iocoder.yudao.module.crm.dal.mysql.customer.CrmCustomerMapper;
import cn.iocoder.yudao.module.crm.dal.mysql.followup.CrmFollowUpRecordMapper;
import cn.iocoder.yudao.module.crm.dal.mysql.permission.CrmPermissionMapper;
import cn.iocoder.yudao.module.crm.framework.permission.core.aop.CrmPermissionAspect;
import cn.iocoder.yudao.module.crm.framework.trial.TrialBusinessAccess;
import cn.iocoder.yudao.module.crm.framework.trial.TrialWebConfiguration;
import cn.iocoder.yudao.module.crm.service.business.CrmBusinessService;
import cn.iocoder.yudao.module.crm.service.clue.CrmClueService;
import cn.iocoder.yudao.module.crm.service.contact.CrmContactService;
import cn.iocoder.yudao.module.crm.service.contract.CrmContractService;
import cn.iocoder.yudao.module.crm.service.customer.*;
import cn.iocoder.yudao.module.crm.service.followup.CrmFollowUpRecordServiceImpl;
import cn.iocoder.yudao.module.crm.service.permission.CrmOwnerRecordService;
import cn.iocoder.yudao.module.crm.service.permission.CrmPermissionServiceImpl;
import cn.iocoder.yudao.module.system.api.dict.DictDataApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.*;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.mockito.Mockito.mock;

/** Isolated real MVC/security/CRM/MyBatis slice. OAuth, system permissions and unrelated services are explicit doubles. */
@Configuration
@EnableWebMvc
@EnableWebSecurity
@EnableMethodSecurity
@EnableAspectJAutoProxy(proxyTargetClass = true)
@EnableTransactionManagement
@Import({SpringUtil.class, TrialStore.class, TrialBusinessService.class, TrialBusinessAccess.class,
        TrialWebConfiguration.class, TrialBusinessController.class, CrmCustomerServiceImpl.class,
        CrmFollowUpRecordServiceImpl.class, CrmPermissionServiceImpl.class, CrmPermissionAspect.class})
class TrialBusinessIntegrationConfiguration implements WebMvcConfigurer {
    @Bean DataSource dataSource() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:trial_business_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        new ResourceDatabasePopulator(new FileSystemResource("../script/trial/V20260914_01__trial_onboarding.sql")).execute(ds);
        createCrmTables(ds, Set.of("crm_customer", "crm_follow_up_record", "crm_permission"));
        return ds;
    }
    static void createCrmTables(DataSource ds, Set<String> tables) throws Exception {
        // Use checked-in production columns, changing only MySQL-specific DDL syntax for H2.
        String source = Files.readString(Path.of("../sql/mysql/modules/crm.sql"));
        var jdbc = new JdbcTemplate(ds);
        for (String table : tables) {
            var matcher = Pattern.compile("CREATE TABLE IF NOT EXISTS `" + table + "` \\(.*?\\) ENGINE=.*?;", Pattern.DOTALL).matcher(source);
            if (!matcher.find()) { throw new IllegalStateException("Missing production CRM DDL: " + table); }
            String ddl = matcher.group().replaceAll("\\) ENGINE=.*?;", ");")
                    .replace("b'0'", "FALSE").replace("bit(1)", "BOOLEAN")
                    .replace("KEY `idx_", "KEY `" + table + "_idx_"); // H2 index names are schema-wide.
            jdbc.execute(ddl);
        }
    }
    @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
    @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
    @Bean TransactionTemplate transactionTemplate(DataSourceTransactionManager manager) { return new TransactionTemplate(manager); }
    @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds); factory.setConfiguration(config);
        factory.setGlobalConfig(new GlobalConfig().setBanner(false).setMetaObjectHandler(new DefaultDBFieldHandler())
                .setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.AUTO)));
        var interceptors = new MybatisPlusInterceptor();
        interceptors.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantDatabaseInterceptor(new TenantProperties())));
        factory.setPlugins(interceptors);
        var sessionFactory = factory.getObject();
        // Register after the factory installs GlobalConfig, just like MapperFactoryBean in the application.
        // Early registration silently loses the production audit-field handler and ID policy.
        sessionFactory.getConfiguration().addMapper(CrmCustomerMapper.class);
        sessionFactory.getConfiguration().addMapper(CrmFollowUpRecordMapper.class);
        sessionFactory.getConfiguration().addMapper(CrmPermissionMapper.class);
        return sessionFactory;
    }
    @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
    @Bean CrmCustomerMapper customerMapper(SqlSessionTemplate sql) { return sql.getMapper(CrmCustomerMapper.class); }
    @Bean CrmFollowUpRecordMapper followUpMapper(SqlSessionTemplate sql) { return sql.getMapper(CrmFollowUpRecordMapper.class); }
    @Bean CrmPermissionMapper permissionMapper(SqlSessionTemplate sql) { return sql.getMapper(CrmPermissionMapper.class); }
    @Bean OAuth2TokenCommonApi tokens() { return mock(OAuth2TokenCommonApi.class); }
    @Bean PermissionCommonApi permissions() { return mock(PermissionCommonApi.class); }
    @Bean AdminUserApi adminUserApi() { return mock(AdminUserApi.class); }
    @Bean DictDataApi dictDataApi() { return mock(DictDataApi.class); }
    @Bean CrmOwnerRecordService ownerRecordService() { return mock(CrmOwnerRecordService.class); }
    @Bean CrmCustomerLimitConfigService customerLimitConfigService() { return mock(CrmCustomerLimitConfigService.class); }
    @Bean CrmCustomerPoolConfigService customerPoolConfigService() { return mock(CrmCustomerPoolConfigService.class); }
    @Bean CrmBusinessService businessService() { return mock(CrmBusinessService.class); }
    @Bean CrmClueService clueService() { return mock(CrmClueService.class); }
    @Bean CrmContactService contactService() { return mock(CrmContactService.class); }
    @Bean CrmContractService contractService() { return mock(CrmContractService.class); }
    @Bean GlobalExceptionHandler errors() { return new GlobalExceptionHandler("trial-isolated-test", mock(ApiErrorLogCommonApi.class)); }
    @Bean(name = "ss") SecurityFrameworkService securityService(PermissionCommonApi permissions) {
        return new SecurityFrameworkServiceImpl(permissions);
    }
    @Bean SecurityFilterChain security(HttpSecurity http, GlobalExceptionHandler errors, OAuth2TokenCommonApi tokens) throws Exception {
        var web = new WebProperties();
        new WebFrameworkUtils(web);
        var tokenFilter = new TokenAuthenticationFilter(new SecurityProperties(), errors, tokens);
        var tenantFilter = new TenantSecurityWebFilter(web, new TenantProperties(), Set.of(), errors, mock(TenantFrameworkService.class));
        return http.csrf(c -> c.disable()).sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(c -> c.anyRequest().authenticated())
                .exceptionHandling(c -> c.authenticationEntryPoint((req, res, ex) -> res.setStatus(401)))
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(tenantFilter, TokenAuthenticationFilter.class).build();
    }
    @Override public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/admin-api", type -> type == TrialBusinessController.class);
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TenantVisitContextInterceptor(new TenantProperties(), securityService(permissions())));
    }
    /** Sentinel handlers prove rejection happens before another module's handler is called; no real file subsystem here. */
    @RestController
    static class DeniedRouteProbe {
        final AtomicInteger calls = new AtomicInteger();
        @GetMapping({"/admin-api/crm/customer/page", "/admin-api/crm/customer/export-excel", "/admin-api/infra/file/1/get/example.txt",
                "/admin-api/crm/clue/page", "/admin-api/system/role/page"})
        public int read() { return calls.incrementAndGet(); }
    }
}
