package cn.iocoder.yudao.module.crm.service.trial;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockServletContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Kills the exact child JVM at a signalled boundary, then starts a new JVM on its file H2 database.
 * Real MGS local provisioning; KnowDo is a separately committed, file-backed fixture, not a network service.
 * This verifies application crash recovery, not MySQL/server/power-loss durability. */
class TrialProcessRestartTest {
    enum Boundary { LOCAL_UNCOMMITTED, LOCAL_COMMITTED, REMOTE_COMMITTED }
    @TempDir Path directory;

    @ParameterizedTest @EnumSource(Boundary.class)
    void coldProcessResumesWithoutDuplicatingResources(Boundary boundary) throws Exception {
        Path signal = directory.resolve("paused.json");
        Process child = start("initial", boundary);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (!Files.exists(signal) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(50);
            assertTrue(Files.exists(signal), "Child failed to reach boundary; log: " + directory.resolve("initial.log"));
            assertTrue(child.isAlive(), "Child must still be paused before forced termination");
            child.destroyForcibly();
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Exact child JVM did not terminate");
            assertNotEquals(0, child.exitValue());
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(15, TimeUnit.SECONDS); }
        }
        var json = new ObjectMapper().findAndRegisterModules();
        var checkpoint = json.readTree(Files.readString(signal));
        String id = checkpoint.path("applicationId").asText();
        var ds = dataSource(directory, "mgs", false);
        var jdbc = new JdbcTemplate(ds);
        var store = new TrialStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)), json);
        assertEquals("DONE", store.step(id, "CRM").state());
        assertEquals(1, count(jdbc, "crm_clue"));
        assertEquals(boundary == Boundary.LOCAL_UNCOMMITTED ? 1 : 2, count(jdbc, "system_users"));
        assertEquals(boundary == Boundary.LOCAL_UNCOMMITTED ? 0 : 1, count(jdbc, "system_role"));
        assertEquals(boundary == Boundary.LOCAL_UNCOMMITTED ? 0 : 1, count(jdbc, "crm_trial_login_delivery"));
        if (boundary == Boundary.LOCAL_UNCOMMITTED) {
            assertEquals("PENDING", store.step(id, "MGS").state());
        } else {
            assertEquals("DONE", store.step(id, "MGS").state());
            assertEquals("RUNNING", store.step(id, "KNOWDO_MEMBER").state());
            // A process exit does not authorize stealing a still-live lease.
            assertNull(store.locked(id, app -> store.claim(id, "KNOWDO_MEMBER")));
            // Accelerate only the 60-second lease expiry in this isolated test; production claim logic is unchanged.
            jdbc.update("UPDATE crm_trial_step SET lease_until=? WHERE application_id=? AND step='KNOWDO_MEMBER'",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(1)), id);
        }
        var remoteJdbc = new JdbcTemplate(dataSource(directory, "knowdo-fixture", false));
        assertEquals(boundary == Boundary.REMOTE_COMMITTED ? 1 : 0, count(remoteJdbc, "fixture_remote"));
        Process resumed = start("resume", boundary);
        try {
            assertTrue(resumed.waitFor(90, TimeUnit.SECONDS), "Recovery timed out");
            assertEquals(0, resumed.exitValue(), "Recovery failed; log: " + directory.resolve("resume.log"));
        } finally {
            if (resumed.isAlive()) { resumed.destroyForcibly(); resumed.waitFor(15, TimeUnit.SECONDS); }
        }
        assertEquals("READY", store.get(id).status());
        for (String step : TrialStore.PROVISION_STEPS) assertEquals("DONE", store.step(id, step).state(), step);
        assertEquals(1, count(jdbc, "crm_trial_application"));
        assertEquals(2, count(jdbc, "system_tenant")); // Only the two preseeded demo/operator tenants.
        assertEquals(2, count(jdbc, "system_users")); // One operator plus one ordinary trial user.
        for (String table : List.of("crm_clue", "crm_customer", "system_role", "system_user_role", "crm_trial_account",
                "crm_trial_authorization", "crm_trial_login_delivery", "system_oauth2_access_token", "system_oauth2_refresh_token")) {
            assertEquals(1, count(jdbc, table), table);
        }
        assertEquals(2, count(jdbc, "system_role_menu"));
        assertEquals(List.of(8L), jdbc.queryForList("SELECT DISTINCT tenant_id FROM crm_clue", Long.class));
        assertEquals(List.of(1L), jdbc.queryForList("SELECT DISTINCT tenant_id FROM crm_customer", Long.class));
        // Verify committed IDs survive, not just final row counts.
        var previous = json.convertValue(checkpoint.path("committedResources"), new TypeReference<Map<String, String>>() {});
        var resources = new TrialOrchestrator(store, null, null).resources(id);
        previous.forEach((key, value) -> assertEquals(value, resources.get(key), key));
        assertEquals(3, count(remoteJdbc, "fixture_remote"));
        assertEquals(3, count(remoteJdbc, "fixture_ensure_calls")); // Each remote ensure called once across both JVMs.
    }

    private Process start(String mode, Boundary boundary) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx384m", "-XX:ActiveProcessorCount=2", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Worker.class.getName(), directory.toString(), mode, boundary.name())
                .redirectErrorStream(true).redirectOutput(directory.resolve(mode + ".log").toFile()).start();
    }
    static int count(JdbcTemplate jdbc, String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    static JdbcDataSource dataSource(Path root, String name, boolean keepOpen) {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:file:" + root.resolve(name).toAbsolutePath()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;NON_KEYWORDS=value;WRITE_DELAY=0;DB_CLOSE_DELAY=" + (keepOpen ? -1 : 0));
        return ds;
    }

    @Configuration
    @org.springframework.cache.annotation.EnableCaching
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
    @org.springframework.context.annotation.EnableAspectJAutoProxy(proxyTargetClass = true)
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class FileConfiguration extends TrialLocalJourneyConfiguration {
        @Bean @Override DataSource dataSource() throws Exception {
            var ds = TrialProcessRestartTest.dataSource(Path.of(System.getProperty("trial.restart.directory")), "mgs", true);
            if (Boolean.getBoolean("trial.restart.initialize")) {
                new ResourceDatabasePopulator(new FileSystemResource("../yudao-module-system/src/test/resources/sql/create_tables.sql"),
                        new FileSystemResource("../script/trial/V20260914_01__trial_onboarding.sql"),
                        new FileSystemResource("../script/trial/V20260914_04__trial_login_delivery.sql")).execute(ds);
                var jdbc = new JdbcTemplate(ds);
                jdbc.execute("ALTER TABLE system_login_log ADD tenant_id BIGINT NOT NULL DEFAULT 0");
                for (String table : List.of("system_oauth2_access_token", "system_oauth2_refresh_token")) {
                    jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN scopes DROP NOT NULL");
                }
                createCrmTables(ds, Set.of("crm_customer", "crm_follow_up_record", "crm_permission", "crm_clue"));
            }
            return ds;
        }
    }

    public static class Worker {
        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[0]); boolean initial = args[1].equals("initial");
            Boundary boundary = Boundary.valueOf(args[2]);
            System.setProperty("trial.restart.directory", directory.toString());
            System.setProperty("trial.restart.initialize", Boolean.toString(initial));
            System.setProperty("mgs.trial.storage-enabled", "true");
            System.setProperty("yudao.captcha.enable", "false");
            try (var context = new AnnotationConfigWebApplicationContext()) {
                context.setServletContext(new MockServletContext()); context.register(FileConfiguration.class); context.refresh();
                var fixture = new TrialLocalJourneyIntegrationTest();
                context.getAutowireCapableBeanFactory().autowireBean(fixture);
                if (initial) fixture.setup(); // Never clear/reseed the database in the recovery JVM.
                var remote = new JdbcTemplate(dataSource(directory, "knowdo-fixture", true));
                remote.execute("CREATE TABLE IF NOT EXISTS fixture_remote (operation VARCHAR(160) PRIMARY KEY,result VARCHAR(4000) NOT NULL)");
                remote.execute("CREATE TABLE IF NOT EXISTS fixture_ensure_calls (operation VARCHAR(160) NOT NULL)");
                String id = initial ? fixture.apply("process-restart")
                        : fixture.json.readTree(Files.readString(directory.resolve("paused.json"))).path("applicationId").asText();
                reset(fixture.knowdo);
                when(fixture.knowdo.lookup(any(), anyString())).thenAnswer(call -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    String step = call.getArgument(1);
                    if (initial && boundary == Boundary.LOCAL_COMMITTED && step.equals("KNOWDO_MEMBER")) pause(directory, fixture, id);
                    var values = remote.queryForList("SELECT result FROM fixture_remote WHERE operation=?", String.class, id + ":" + step);
                    return values.isEmpty() ? new KnowdoTrialAdapter.Result(KnowdoTrialAdapter.LookupState.ABSENT, Map.of())
                            : new KnowdoTrialAdapter.Result(KnowdoTrialAdapter.LookupState.COMPLETE,
                            fixture.json.readValue(values.get(0), new TypeReference<Map<String, String>>() {}));
                });
                when(fixture.knowdo.ensure(any(), anyString(), anyMap())).thenAnswer(call -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    String step = call.getArgument(1);
                    Map<String, String> result = switch (step) {
                        case "KNOWDO_MEMBER" -> Map.of("knowdoMemberId", "fixture-member-" + id, "knowdoTenantId", "fixture-tenant");
                        case "KNOWDO_AUTH" -> Map.of("knowdoAuthorizationId", "fixture-auth-" + id);
                        case "DELIVERY" -> Map.of("deliveryRef", "fixture-card-" + id);
                        default -> throw new AssertionError(step);
                    };
                    remote.update("INSERT INTO fixture_ensure_calls(operation) VALUES(?)", id + ":" + step);
                    remote.update("INSERT INTO fixture_remote(operation,result) VALUES(?,?)", id + ":" + step, fixture.json.writeValueAsString(result));
                    if (initial && boundary == Boundary.REMOTE_COMMITTED && step.equals("KNOWDO_MEMBER")) pause(directory, fixture, id);
                    return new KnowdoTrialAdapter.Result(KnowdoTrialAdapter.LookupState.COMPLETE, result);
                });
                TrialLocalProvisioner local = (app, step, resources) -> {
                    var result = fixture.local.execute(app, step, resources);
                    if (initial && boundary == Boundary.LOCAL_UNCOMMITTED && step.equals("MGS")) {
                        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                        pause(directory, fixture, id);
                    }
                    return result;
                };
                var orchestrator = new TrialOrchestrator(fixture.store, local, fixture.knowdo);
                orchestrator.advance(id);
                assertFalse(initial, "Expected a forced process termination");
                assertEquals("READY", fixture.store.get(id).status());
                orchestrator.advance(id); // A repeat after recovery must also be a no-op.
            }
        }
        static void pause(Path directory, TrialLocalJourneyIntegrationTest fixture, String id) {
            try {
                var content = fixture.json.writeValueAsString(Map.of("applicationId", id,
                        "committedResources", fixture.orchestrator.resources(id)));
                Path temporary = directory.resolve("paused.tmp"); Files.writeString(temporary, content);
                Files.move(temporary, directory.resolve("paused.json"), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                new CountDownLatch(1).await(); // Only this test's parent process terminates this JVM.
            } catch (Exception e) { throw new IllegalStateException("Could not signal isolated test boundary", e); }
        }
    }
}
