package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.system.dal.mysql.permission.MenuMapper;
import cn.iocoder.yudao.module.system.dal.mysql.tenant.TenantMapper;
import cn.iocoder.yudao.module.system.dal.mysql.tenant.TenantPackageMapper;
import cn.iocoder.yudao.module.system.service.permission.MenuService;
import cn.iocoder.yudao.module.system.service.permission.MenuServiceImpl;
import cn.iocoder.yudao.module.system.service.tenant.*;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.dynamic.datasource.aop.DynamicLocalTransactionInterceptor;
import com.baomidou.dynamic.datasource.tx.TransactionContext;
import jakarta.annotation.Resource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.context.annotation.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Real corporate Tenant/Package/AdminUser/Role/Permission services and both transaction families.
 * Reuses the isolated account fixture; only dept/post/config/MQ/CRM/OAuth boundaries are doubles; menu queries use the real mapper/service. */
@SpringJUnitConfig(TrialCorporateTenantIntegrationTest.Config.class)
@TestPropertySource(properties = "mgs.trial.storage-enabled=true")
class TrialCorporateTenantIntegrationTest {
    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @org.springframework.transaction.annotation.EnableTransactionManagement
    @Import(TrialOperatorBootstrapService.class)
    static class Config extends MgsTrialAccountIntegrationTest.Config {
        @Bean @Override DataSource dataSource() {
            DataSource raw = super.dataSource();
            new ResourceDatabasePopulator(new FileSystemResource("../script/trial/V20260914_03__trial_operator_setup.sql")).execute(raw);
            var routing = new DynamicRoutingDataSource(List.of());
            routing.setPrimary("master"); routing.addDataSource("master", raw); return routing;
        }
        @Bean @Override TenantService tenantService() { return new TenantServiceImpl(); }
        @Bean @Override MenuService menuService() { return new MenuServiceImpl(); }
        @Bean TenantPackageService tenantPackageService() { return new TenantPackageServiceImpl(); }
        @Bean TenantProperties tenantProperties() { var p = new TenantProperties(); p.setEnable(true); return p; }
        @Bean TrialOperatorBootstrapProperties bootstrapProperties() { return new TrialOperatorBootstrapProperties(); }
        @Bean Advisor dynamicTransactions() {
            return new DefaultPointcutAdvisor(new AnnotationMatchingPointcut(null, DSTransactional.class, true),
                    new DynamicLocalTransactionInterceptor(true));
        }
        @Bean @Override SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var factory = super.sqlSessionFactory(ds);
            factory.getConfiguration().addMapper(MenuMapper.class);
            factory.getConfiguration().addMapper(TenantMapper.class);
            factory.getConfiguration().addMapper(TenantPackageMapper.class); return factory;
        }
        @Bean MenuMapper menuMapper(SqlSessionTemplate t) { return t.getMapper(MenuMapper.class); }
        @Bean TenantMapper tenantMapper(SqlSessionTemplate t) { return t.getMapper(TenantMapper.class); }
        @Bean TenantPackageMapper tenantPackageMapper(SqlSessionTemplate t) { return t.getMapper(TenantPackageMapper.class); }
    }
    @Resource JdbcTemplate jdbc;
    @Resource TrialOperatorBootstrapService bootstrap;
    @Resource TrialOperatorBootstrapProperties properties;
    @Resource TenantService tenants;
    @Resource AdminUserService users;
    @Resource MenuService menus;
    @Resource TrialStore store;
    @Resource MgsTrialProvisioner provisioner;

    @BeforeEach void setup() {
        TenantContextHolder.clear();
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(bootstrap), "bootstrap must use actual transaction advice");
        for (String table : List.of("crm_trial_login_delivery", "crm_trial_account", "crm_trial_step", "crm_trial_application",
                "crm_trial_operator_setup", "system_user_role", "system_role_menu", "system_user_post", "system_users", "system_role",
                "system_tenant", "system_tenant_package", "system_menu")) { jdbc.update("DELETE FROM " + table); }
        jdbc.execute("ALTER TABLE system_tenant ALTER COLUMN id RESTART WITH 2");
        jdbc.update("INSERT INTO system_tenant(id,name,contact_name,status,package_id,expire_time,account_count) VALUES(1,'演示环境','内部演示管理',0,0,?,20)", LocalDateTime.now().plusYears(1));
        properties.setEnabled(true); properties.setContactName("内部运营测试人"); properties.setUsername("qiyunfixture");
        properties.setPassword("FixturePass12345"); properties.setAccountCount(20);
        properties.setExpireTime(LocalDateTime.now().plusDays(30).withNano(0)); properties.setMenuIds(Set.of(10L, 11L, 12L));
        for (var entry : Map.of(10L, "crm:trial:query", 11L, "crm:trial:recover", 12L, "crm:clue:query",
                21L, "crm:trial-business:query", 22L, "crm:trial-business:follow-up").entrySet()) {
            jdbc.update("INSERT INTO system_menu(id,name,permission,type,parent_id,status) VALUES(?,?,?,3,0,0)",
                    entry.getKey(), "fixture-" + entry.getKey(), entry.getValue());
        }
    }
    @AfterEach void clearTenant() { TenantContextHolder.clear(); assertNull(TransactionContext.getXID()); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    @Test void actualCorporateInitializationIsSeparateFromOrdinaryDemoAccount() {
        var result = bootstrap.initialize();
        assertTrue(result.operatorTenantId() > 1); assertEquals(2, count("system_tenant"));
        var tenant = tenants.getTenant(result.operatorTenantId());
        assertEquals("栖云", tenant.getName()); assertEquals(result.ownerUserId(), tenant.getContactUserId());
        TenantUtils.execute(result.operatorTenantId(), () -> {
            var owner = users.getUser(result.ownerUserId());
            assertEquals(properties.getUsername(), owner.getUsername());
            assertTrue(users.isPasswordMatch(properties.getPassword(), owner.getPassword()));
        });
        assertEquals("tenant_admin", jdbc.queryForObject("SELECT code FROM system_role WHERE tenant_id=?", String.class, result.operatorTenantId()));
        assertEquals(Set.of(10L, 11L, 12L), Set.copyOf(jdbc.queryForList("SELECT menu_id FROM system_role_menu WHERE tenant_id=?", Long.class, result.operatorTenantId())));
        var identity = new TrialIdentity("knowdo", "verified-fixture", "fixture@example.invalid", "confirmed", "fixture-creation-key");
        var policy = new TrialProperties.Policy("fixture", result.operatorTenantId(), result.ownerUserId(), 1, 7,
                "https://mgs.example.invalid", "https://knowdo.example.invalid", "fixture-client");
        var app = store.submit(identity, identity.idempotencyKey(), "虚构体验团队", "体验人", "CRM_FOLLOW_UP", policy, 20);
        store.confirm(app.id(), identity);
        var account = store.locked(app.id(), a -> provisioner.execute(a, "MGS", Map.of()));
        assertNotEquals(result.ownerUserId(), Long.parseLong(account.get("userId")));
        assertEquals(1L, jdbc.queryForObject("SELECT tenant_id FROM system_users WHERE id=?", Long.class, Long.parseLong(account.get("userId"))));
        assertEquals(Set.of(21L, 22L), Set.copyOf(jdbc.queryForList("SELECT menu_id FROM system_role_menu WHERE tenant_id=1", Long.class)));
        assertTrue(jdbc.queryForObject("SELECT code FROM system_role WHERE tenant_id=1", String.class).startsWith("mgs_trial_"));
        assertEquals(2, count("system_tenant")); // No per-visitor tenant or administrator in the shared demo environment.
    }

    @Test void concurrentInitializationCreatesOneRealTenantAndNeverResetsItsOwner() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            var futures = pool.invokeAll(java.util.stream.IntStream.range(0, 8)
                    .mapToObj(i -> (Callable<TrialOperatorBootstrapService.Result>) bootstrap::initialize).toList());
            var first = futures.get(0).get();
            for (var future : futures) assertEquals(first, future.get());
            assertEquals(2, count("system_tenant")); assertEquals(1, count("system_tenant_package"));
            assertEquals(1, count("system_users")); assertEquals(1, count("system_role"));
            assertEquals(1, count("system_user_role")); assertEquals(3, count("system_role_menu"));
            String hash = jdbc.queryForObject("SELECT password FROM system_users WHERE id=?", String.class, first.ownerUserId());
            properties.setPassword("ChangedPass12345"); assertEquals(first, bootstrap.initialize());
            assertEquals(hash, jdbc.queryForObject("SELECT password FROM system_users WHERE id=?", String.class, first.ownerUserId()));
        } finally { pool.shutdownNow(); }
    }

    @Test void registryFailureRollsBackRealTenantPackageOwnerRoleAndPermissions() {
        jdbc.execute("ALTER TABLE crm_trial_operator_setup ADD CONSTRAINT fixture_reject_setup CHECK(tenant_id < 0)");
        try {
            var error = assertThrows(RuntimeException.class, bootstrap::initialize);
            assertTrue(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(error).getMessage().contains("fixture_reject_setup"));
        } finally { jdbc.execute("ALTER TABLE crm_trial_operator_setup DROP CONSTRAINT fixture_reject_setup"); }
        assertEquals(1, count("system_tenant"));
        for (String table : List.of("system_tenant_package", "system_users", "system_role", "system_user_role", "system_role_menu", "crm_trial_operator_setup")) assertEquals(0, count(table), table);
        assertTrue(bootstrap.initialize().operatorTenantId() > 1);
        assertEquals(2, count("system_tenant")); assertEquals(1, count("system_users"));
    }
    @Test void disabledActualMenuIsRejectedBeforeAnyCorporateResourceIsCreated() {
        jdbc.update("UPDATE system_menu SET status=1 WHERE id=11");
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, bootstrap::initialize);
        assertEquals(1, count("system_tenant")); assertEquals(0, count("system_tenant_package"));
        assertEquals(0, count("system_users")); assertEquals(0, count("crm_trial_operator_setup"));
    }

}
