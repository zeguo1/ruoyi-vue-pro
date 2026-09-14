package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.system.controller.admin.tenant.vo.tenant.TenantSaveReqVO;
import cn.iocoder.yudao.module.system.dal.dataobject.permission.MenuDO;
import cn.iocoder.yudao.module.system.dal.dataobject.tenant.TenantDO;
import cn.iocoder.yudao.module.system.service.permission.MenuService;
import cn.iocoder.yudao.module.system.service.tenant.TenantPackageService;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.dynamic.datasource.aop.DynamicLocalTransactionInterceptor;
import com.baomidou.dynamic.datasource.tx.TransactionContext;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real dynamic-datasource local transactions/locks, including nested Spring transactions.
 * Tenant, package and user services are explicit doubles; does not claim actual tenant creation was integrated. */
class TrialOperatorBootstrapTest {
    JdbcTemplate jdbc;
    DynamicRoutingDataSource ds;
    TrialOperatorBootstrapProperties properties;
    TrialOperatorBootstrapService bootstrap;
    TenantService tenants;
    TenantPackageService packages;
    MenuService menus;
    AdminUserService users;

    @BeforeEach void setup() {
        var raw = new JdbcDataSource();
        raw.setURL("jdbc:h2:mem:trial_setup_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        new ResourceDatabasePopulator(new FileSystemResource("../script/trial/V20260914_01__trial_onboarding.sql"),
                new FileSystemResource("../script/trial/V20260914_03__trial_operator_setup.sql")).execute(raw);
        ds = new DynamicRoutingDataSource(List.of()); ds.setPrimary("master"); ds.addDataSource("master", raw);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE fixture_setup_effect(resource VARCHAR(32) PRIMARY KEY)");
        properties = new TrialOperatorBootstrapProperties(); properties.setEnabled(true);
        properties.setContactName("内部运营测试人"); properties.setUsername("qiyuntest"); properties.setPassword("FixturePass12345");
        properties.setExpireTime(LocalDateTime.now().plusDays(30)); properties.setAccountCount(10); properties.setMenuIds(Set.of(10L, 11L, 12L));
        tenants = mock(TenantService.class); packages = mock(TenantPackageService.class);
        menus = mock(MenuService.class); users = mock(AdminUserService.class);
        when(menus.getMenuList(properties.getMenuIds())).thenReturn(List.of(
                menu(10, "crm:trial:query"), menu(11, "crm:trial:recover"), menu(12, "crm:clue:query")));
        when(packages.createTenantPackage(any())).thenAnswer(call -> {
            assertNotNull(TransactionContext.getXID()); jdbc.update("INSERT INTO fixture_setup_effect VALUES('package')"); return 80L;
        });
        var nested = proxy(new NestedTenantFixture(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds))));
        when(tenants.createTenant(any())).thenAnswer(call -> {
            TenantSaveReqVO request = call.getArgument(0);
            assertEquals("栖云", request.getName()); assertEquals(80L, request.getPackageId());
            return nested.create();
        });
        when(tenants.getTenant(8L)).thenReturn(new TenantDO().setId(8L).setName("栖云").setPackageId(80L).setContactUserId(90L));
        doAnswer(call -> { assertEquals(8L, TenantContextHolder.getTenantId()); return null; }).when(users).validateUserList(List.of(90L));
        bootstrap = proxy(new TrialOperatorBootstrapService(properties, jdbc, tenants, packages, menus, users));
    }
    private static MenuDO menu(long id, String permission) { return new MenuDO().setId(id).setPermission(permission).setStatus(0).setParentId(0L); }
    @SuppressWarnings("unchecked") private static <T> T proxy(T target) {
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new DynamicLocalTransactionInterceptor(true)); return (T) factory.getProxy();
    }
    static class NestedTenantFixture {
        private final JdbcTemplate jdbc;
        private final TransactionTemplate springTransaction;
        NestedTenantFixture(JdbcTemplate jdbc, TransactionTemplate springTransaction) { this.jdbc = jdbc; this.springTransaction = springTransaction; }
        @DSTransactional public Long create() {
            jdbc.update("INSERT INTO fixture_setup_effect VALUES('tenant')");
            // Mirrors nested AdminUserService @Transactional under TenantService @DSTransactional.
            return springTransaction.execute(tx -> { jdbc.update("INSERT INTO fixture_setup_effect VALUES('internal-user')"); return 8L; });
        }
    }
    int effects() { return jdbc.queryForObject("SELECT COUNT(*) FROM fixture_setup_effect", Integer.class); }

    @Test void concurrentInitializationCreatesOnceAndReturnsOnlyNonSecretIds() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            var futures = pool.invokeAll(java.util.stream.IntStream.range(0, 8).mapToObj(i -> (Callable<TrialOperatorBootstrapService.Result>) bootstrap::initialize).toList());
            for (var future : futures) { assertEquals(new TrialOperatorBootstrapService.Result(8, 90, 80), future.get()); }
        } finally { pool.shutdownNow(); }
        assertEquals(3, effects()); assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM crm_trial_operator_setup", Integer.class));
        verify(tenants, times(1)).createTenant(any()); verify(packages, times(1)).createTenantPackage(any());
        assertFalse(properties.toString().contains(properties.getPassword()));
        // Rotating deployment secret doesn't reset an existing internal administrator password.
        properties.setPassword("ChangedPass12345"); bootstrap.initialize(); verify(tenants, times(1)).createTenant(any());
    }
    @Test void lateFailureRollsBackPackageTenantUserAndRegistryAcrossBothTransactionTypes() {
        when(tenants.getTenant(8L)).thenReturn(null);
        assertThrows(ServiceException.class, bootstrap::initialize);
        assertEquals(0, effects()); assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM crm_trial_operator_setup", Integer.class));
        when(tenants.getTenant(8L)).thenReturn(new TenantDO().setId(8L).setName("栖云").setPackageId(80L).setContactUserId(90L));
        assertEquals(8, bootstrap.initialize().operatorTenantId()); assertEquals(3, effects());
        assertNull(TransactionContext.getXID());
    }
    @Test void unconfiguredOrPreexistingTenantIsNotCreatedOrTakenOver() {
        properties.setEnabled(false); assertThrows(ServiceException.class, bootstrap::initialize); assertEquals(0, effects());
        properties.setEnabled(true); when(tenants.getTenantByName("栖云")).thenReturn(new TenantDO().setId(5L));
        assertThrows(ServiceException.class, bootstrap::initialize); assertEquals(0, effects());
        verify(packages, never()).createTenantPackage(any());
    }
    @Test void changedConfigurationOrBrokenRegistryNeverOverwritesExistingResources() {
        bootstrap.initialize(); properties.setAccountCount(20);
        assertThrows(ServiceException.class, bootstrap::initialize); assertEquals(3, effects());
        properties.setAccountCount(10); jdbc.update("UPDATE crm_trial_operator_setup SET tenant_id=1");
        assertThrows(ServiceException.class, bootstrap::initialize); verify(tenants, times(1)).createTenant(any());
    }
    @Test void missingMenuAncestorsOrOperationalPermissionsFailBeforeCreatingResources() {
        when(menus.getMenuList(properties.getMenuIds())).thenReturn(List.of(menu(10, "crm:trial:query"), menu(11, "crm:trial:recover"),
                menu(12, "crm:clue:query").setParentId(99L)));
        assertThrows(ServiceException.class, bootstrap::initialize); assertEquals(0, effects());
        when(menus.getMenuList(properties.getMenuIds())).thenReturn(List.of(menu(10, "crm:trial:query")));
        assertThrows(ServiceException.class, bootstrap::initialize); verify(packages, never()).createTenantPackage(any());
    }
}
