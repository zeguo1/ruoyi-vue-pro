package cn.iocoder.yudao.module.crm.service.trial;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in MySQL 8.4 persistence regression. Owns its temporary container; never accepts an external JDBC URL.
 * Inherits the 11 orchestration cases. Local business side effects and KnowDo remain explicit fixtures.
 */
@EnabledIfEnvironmentVariable(named = "MGS_TRIAL_TEST_MYSQL", matches = "true")
class TrialMySqlIntegrationTest extends TrialOrchestratorTest {
    private static String containerId;
    private static String rootUrl;
    private static final String password = UUID.randomUUID().toString();
    private static Thread cleanupHook;
    private DriverManagerDataSource database;

    @BeforeAll static void startIsolatedMySql() throws Exception {
        // --pull=never prevents surprise downloads; no host mounts or existing volumes are used.
        containerId = docker(Map.of("MYSQL_ROOT_PASSWORD", password), "create", "--pull=never", "--rm",
                "--name", "mgs-trial-mysql-test-" + UUID.randomUUID(),
                "--label", "mgs.trial.isolated-test=true", "--memory=512m", "--memory-swap=512m", "--cpus=1",
                "--publish", "127.0.0.1::3306", "--env", "MYSQL_ROOT_PASSWORD", "mysql:8.4",
                "--innodb-buffer-pool-size=64M", "--performance-schema=OFF", "--max-connections=24", "--skip-log-bin");
        assertTrue(containerId.matches("[a-f0-9]{64}"));
        cleanupHook = new Thread(TrialMySqlIntegrationTest::cleanup, "trial-mysql-test-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanupHook);
        docker(Map.of(), "start", containerId);
        String address = docker(Map.of(), "port", containerId, "3306/tcp");
        assertTrue(address.matches("127\\.0\\.0\\.1:[0-9]+"), address);
        rootUrl = "jdbc:mysql://" + address + "/";
        Instant deadline = Instant.now().plusSeconds(90);
        while (true) {
            try (var connection = dataSource("").getConnection()) {
                assertTrue(connection.isValid(2));
                break;
            } catch (java.sql.SQLException exception) {
                if (Instant.now().isAfter(deadline)) { throw exception; }
                TimeUnit.MILLISECONDS.sleep(500);
            }
        }
        var jdbc = new JdbcTemplate(dataSource(""));
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version.startsWith("8.4."));
        Files.writeString(Path.of("target/trial-mysql-runtime.json"), new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(Map.of("version", version, "containerId", containerId,
                        "imageId", docker(Map.of(), "inspect", "--format={{.Image}}", containerId),
                        "memoryLimitBytes", 512L * 1024 * 1024, "temporaryDatabaseOnly", true,
                        "realKnowdoVerified", false, "realBusinessProvisioningVerified", false)));
    }

    private static DriverManagerDataSource dataSource(String schema) {
        return new DriverManagerDataSource(rootUrl + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&connectTimeout=1500&socketTimeout=15000",
                "root", password);
    }

    @Override @BeforeEach void setup() {
        String schema = "trial_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(dataSource("")).execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        database = dataSource(schema);
        configure(database);
    }

    @Test void originalMigrationsReplayWithoutReplacingMenusOrApplicationState() throws Exception {
        String baseline = Files.readString(Path.of("../sql/mysql/ruoyi-vue-pro.sql"));
        var menu = Pattern.compile("CREATE TABLE `system_menu`.*?;", Pattern.DOTALL).matcher(baseline);
        assertTrue(menu.find());
        new ResourceDatabasePopulator(new ByteArrayResource(menu.group().getBytes(StandardCharsets.UTF_8))).execute(database);
        applyMigrations();
        var app = submit(); confirm(app.id()); orchestrator.advance(app.id());
        var menus = jdbc.queryForList("SELECT id,name,permission,type,parent_id FROM system_menu ORDER BY id");
        assertEquals(4, menus.size());
        for (String permission : List.of("crm:trial:recover", "crm:trial-business:follow-up")) {
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM system_menu button JOIN system_menu page ON button.parent_id=page.id WHERE button.permission=? AND button.type=3 AND page.type=2", Integer.class, permission));
        }
        // Exercise the real TIMESTAMP(6) escrow migration and distinct registry tables too.
        jdbc.update("INSERT INTO crm_trial_login_delivery(application_id,user_id,encryption_key_id,nonce,ciphertext,claim_hash,retry_until,created_at) VALUES(?,100,'fixture-key','fixture-nonce','fixture-cipher','fixture-hash','2026-09-15 00:00:01.123456','2026-09-15 00:00:00.123456')", app.id());
        jdbc.update("INSERT INTO crm_trial_operator_setup VALUES('fixture','fixture-hash',8,9,10,'2026-09-15 00:00:00.123456')");
        applyMigrations();
        assertEquals(menus, jdbc.queryForList("SELECT id,name,permission,type,parent_id FROM system_menu ORDER BY id"));
        assertEquals("READY", store.get(app.id()).status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM crm_trial_application", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM crm_trial_operator_setup", Integer.class));
        assertEquals(123456, jdbc.queryForObject("SELECT MICROSECOND(retry_until) FROM crm_trial_login_delivery", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM crm_trial_guard", Integer.class));
    }

    private void applyMigrations() {
        for (String suffix : List.of("01__trial_onboarding", "02__trial_menus", "03__trial_operator_setup", "04__trial_login_delivery")) {
            new ResourceDatabasePopulator(new FileSystemResource("../script/trial/V20260914_" + suffix + ".sql")).execute(database);
        }
    }

    private static String docker(Map<String, String> environment, String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>(); command.add("docker"); command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(environment);
        var process = builder.start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Isolated MySQL Docker command timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) { throw new IllegalStateException("Isolated MySQL Docker command failed: " + output); }
        return output;
    }

    private static synchronized void cleanup() {
        if (containerId == null) { return; }
        try {
            // Only the exact container created by this test. -v removes its anonymous data volume.
            docker(Map.of(), "rm", "-f", "-v", containerId);
            containerId = null;
        } catch (Exception exception) { throw new IllegalStateException("Could not remove isolated MySQL test container", exception); }
    }

    @AfterAll static void stopIsolatedMySql() {
        cleanup();
        if (cleanupHook != null) { Runtime.getRuntime().removeShutdownHook(cleanupHook); }
    }
}
