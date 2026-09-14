package cn.iocoder.yudao.module.crm.service.trial;

import cn.hutool.extra.spring.SpringUtil;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.crm.service.clue.CrmClueService;
import cn.iocoder.yudao.module.crm.service.customer.CrmCustomerService;
import cn.iocoder.yudao.module.system.dal.dataobject.permission.MenuDO;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantDO;
import cn.iocoder.yudao.module.system.dal.mysql.permission.*;
import cn.iocoder.yudao.module.system.dal.mysql.user.AdminUserMapper;
import cn.iocoder.yudao.module.system.dal.mysql.dept.UserPostMapper;
import cn.iocoder.yudao.module.system.enums.permission.DataScopeEnum;
import cn.iocoder.yudao.module.system.mq.producer.user.AdminUserProducer;
import cn.iocoder.yudao.module.system.service.dept.DeptService;
import cn.iocoder.yudao.module.system.service.dept.PostService;
import cn.iocoder.yudao.module.system.service.oauth2.OAuth2TokenService;
import cn.iocoder.yudao.module.system.service.permission.*;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import cn.iocoder.yudao.module.system.service.tenant.handler.TenantInfoHandler;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import cn.iocoder.yudao.module.system.service.user.AdminUserServiceImpl;
import cn.iocoder.yudao.module.infra.api.config.ConfigApi;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual MGS provisioning + AdminUser/Role/Permission services/MyBatis/BCrypt/encrypted login delivery.
 * Tenant metadata/menu directory, CRM and OAuth are explicit doubles. No live accounts or production DB. */
@SpringJUnitConfig(MgsTrialAccountIntegrationTest.Config.class)
@TestPropertySource(properties = "mgs.trial.storage-enabled=true")
class MgsTrialAccountIntegrationTest {
    @Configuration
    @EnableTransactionManagement
    @Import({SpringUtil.class, TrialStore.class, TrialLoginVault.class, TrialLoginDeliveryService.class, MgsTrialProvisioner.class,
            AdminUserServiceImpl.class, RoleServiceImpl.class, PermissionServiceImpl.class})
    static class Config {
        @Bean DataSource dataSource() {
            var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:account_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;NON_KEYWORDS=value");
            new ResourceDatabasePopulator(new FileSystemResource("../yudao-module-system/src/test/resources/sql/create_tables.sql"),
                    new FileSystemResource("../script/trial/V20260914_01__trial_onboarding.sql"),
                    new FileSystemResource("../script/trial/V20260914_04__trial_login_delivery.sql")).execute(ds);
            return ds;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean TransactionTemplate transactionTemplate(DataSourceTransactionManager manager) { return new TransactionTemplate(manager); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean TrialProperties trialProperties() {
            var p = new TrialProperties(); p.getLoginDelivery().setActiveKeyId("fixture");
            p.getLoginDelivery().setEncryptionKeys(Map.of("fixture", Base64.getEncoder().encodeToString(new byte[32]))); return p;
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
            factory.setGlobalConfig(new GlobalConfig().setBanner(false).setMetaObjectHandler(new DefaultDBFieldHandler())
                    .setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.AUTO)));
            var interceptor = new MybatisPlusInterceptor(); interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantDatabaseInterceptor(new TenantProperties())));
            factory.setPlugins(interceptor);
            var sessions = factory.getObject();
            for (var mapper : List.of(AdminUserMapper.class, RoleMapper.class, RoleMenuMapper.class, UserRoleMapper.class, UserPostMapper.class)) {
                sessions.getConfiguration().addMapper(mapper);
            }
            return sessions;
        }
        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory f) { return new SqlSessionTemplate(f); }
        @Bean AdminUserMapper userMapper(SqlSessionTemplate t) { return t.getMapper(AdminUserMapper.class); }
        @Bean RoleMapper roleMapper(SqlSessionTemplate t) { return t.getMapper(RoleMapper.class); }
        @Bean RoleMenuMapper roleMenuMapper(SqlSessionTemplate t) { return t.getMapper(RoleMenuMapper.class); }
        @Bean UserRoleMapper userRoleMapper(SqlSessionTemplate t) { return t.getMapper(UserRoleMapper.class); }
        @Bean UserPostMapper userPostMapper(SqlSessionTemplate t) { return t.getMapper(UserPostMapper.class); }
        @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(4); }
        @Bean TenantService tenantService() { return mock(TenantService.class); }
        @Bean MenuService menuService() { return mock(MenuService.class); }
        @Bean DeptService deptService() { return mock(DeptService.class); }
        @Bean PostService postService() { return mock(PostService.class); }
        @Bean OAuth2TokenService oauth2TokenService() { return mock(OAuth2TokenService.class); }
        @Bean ConfigApi configApi() { return mock(ConfigApi.class); }
        @Bean AdminUserProducer adminUserProducer() { return mock(AdminUserProducer.class); }
        @Bean CrmClueService clues() { return mock(CrmClueService.class); }
        @Bean CrmCustomerService customers() { return mock(CrmCustomerService.class); }
        @Bean TrialOAuthGateway oauth() { return mock(TrialOAuthGateway.class); }
        @Bean TrialAuthorizationService authorizations() { return mock(TrialAuthorizationService.class); }
    }
    @Resource JdbcTemplate jdbc;
    @Resource TrialStore store;
    @Resource MgsTrialProvisioner provisioner;
    @Resource TrialLoginDeliveryService delivery;
    @Resource AdminUserService users;
    @Resource TenantService tenants;
    @Resource MenuService menus;
    @Resource TrialProperties properties;

    @BeforeEach void setup() {
        TenantContextHolder.clear();
        for (String table : List.of("crm_trial_login_delivery", "crm_trial_account", "crm_trial_step", "crm_trial_application",
                "system_user_role", "system_role_menu", "system_user_post", "system_users", "system_role")) { jdbc.update("DELETE FROM " + table); }
        reset(tenants, menus);
        doAnswer(call -> { ((TenantInfoHandler) call.getArgument(0)).handle(new TenantDO().setId(1L).setAccountCount(10)); return null; }).when(tenants).handleTenantInfo(any());
        for (var item : Map.of(11L, "crm:trial-business:query", 12L, "crm:trial-business:follow-up").entrySet()) {
            when(menus.getMenuIdListByPermissionFromCache(item.getValue())).thenReturn(List.of(item.getKey()));
            when(menus.getMenu(item.getKey())).thenReturn(new MenuDO().setId(item.getKey()).setStatus(0).setParentId(0L).setPermission(item.getValue()));
        }
    }
    TrialStore.Application application(String subject) {
        var identity = identity(subject);
        var policy = new TrialProperties.Policy("fixture", 8, 90, 1, 7, "https://mgs.example.invalid", "https://knowdo.example.invalid", "fixture-client");
        var app = store.submit(identity, identity.idempotencyKey(), "虚构团队", "体验人", "CRM_FOLLOW_UP", policy, 20);
        store.confirm(app.id(), identity); return store.get(app.id());
    }
    TrialIdentity identity(String subject) { return new TrialIdentity("knowdo", subject, subject + "@example.invalid", "confirmed", "claim-request-" + subject); }
    Map<String, String> create(TrialStore.Application app) {
        return store.locked(app.id(), a -> { var result = provisioner.execute(a, "MGS", Map.of()); store.localDone(a.id(), "MGS", result); return result; });
    }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    @Test void twoOrdinaryAccountsHaveDistinctSelfScopedRolesAndRealLoginPasswords() {
        var first = application("first-person"); var second = application("second-person");
        var one = create(first); var two = create(second);
        assertNotEquals(one.get("roleId"), two.get("roleId")); assertNotEquals(one.get("userId"), two.get("userId"));
        assertEquals(2, count("system_users")); assertEquals(2, count("system_role")); assertEquals(4, count("system_role_menu"));
        assertEquals(Set.of(DataScopeEnum.SELF.getScope()), Set.copyOf(jdbc.queryForList("SELECT data_scope FROM system_role", Integer.class)));
        assertEquals(Set.of(1L), Set.copyOf(jdbc.queryForList("SELECT tenant_id FROM system_users", Long.class)));
        assertTrue(jdbc.queryForList("SELECT code FROM system_role", String.class).stream().allMatch(code -> code.startsWith("mgs_trial_")));
        for (var step : TrialStore.PROVISION_STEPS) { if (!step.equals("MGS")) store.localDone(first.id(), step, Map.of()); }
        store.status(first.id(), "READY"); var credential = delivery.claim(first.id(), identity("first-person"));
        var user = cn.iocoder.yudao.framework.tenant.core.util.TenantUtils.execute(1L, () -> users.getUser(Long.parseLong(one.get("userId"))));
        assertEquals(user.getUsername(), credential.username()); assertTrue(users.isPasswordMatch(credential.password(), user.getPassword()));
        assertFalse(user.getPassword().equals(credential.password()));
        store.locked(first.id(), a -> provisioner.execute(a, "MGS_REVOKE", one));
        assertEquals(1, jdbc.queryForObject("SELECT status FROM system_users WHERE id=?", Integer.class, user.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM system_user_role WHERE user_id=? AND deleted=FALSE", Integer.class, user.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT status FROM system_users WHERE id=?", Integer.class, Long.parseLong(two.get("userId"))));
        assertNull(jdbc.queryForObject("SELECT ciphertext FROM crm_trial_login_delivery WHERE application_id=?", String.class, first.id()));
        verify(tenants, never()).createTenant(any());
    }
    @Test void escrowFailureRollsBackActualUserRoleAndPermissionAssignments() {
        var app = application("failure-person");
        jdbc.execute("ALTER TABLE crm_trial_login_delivery ADD CONSTRAINT fixture_no_escrow CHECK(user_id < 0)");
        try {
            var ex = assertThrows(RuntimeException.class, () -> create(app));
            assertTrue(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(ex).getMessage().contains("fixture_no_escrow"));
        }
        finally { jdbc.execute("ALTER TABLE crm_trial_login_delivery DROP CONSTRAINT fixture_no_escrow"); }
        for (String table : List.of("system_users", "system_role", "system_user_role", "system_role_menu", "crm_trial_login_delivery", "crm_trial_account")) { assertEquals(0, count(table), table); }
        assertEquals("PENDING", store.step(app.id(), "MGS").state());
        create(app); assertEquals(1, count("system_users"));
    }
    @Test void realUserQuotaFailureLeavesNoRoleOrEncryptedCredential() {
        doAnswer(call -> { ((TenantInfoHandler) call.getArgument(0)).handle(new TenantDO().setId(1L).setAccountCount(0)); return null; }).when(tenants).handleTenantInfo(any());
        var ex = assertThrows(RuntimeException.class, () -> create(application("quota-person")));
        assertInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class, org.springframework.core.NestedExceptionUtils.getMostSpecificCause(ex));
        assertEquals(0, count("system_users")); assertEquals(0, count("system_role")); assertEquals(0, count("crm_trial_login_delivery"));
    }
}
