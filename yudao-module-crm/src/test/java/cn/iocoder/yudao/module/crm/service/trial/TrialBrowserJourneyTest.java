package cn.iocoder.yudao.module.crm.service.trial;

import cn.iocoder.yudao.framework.common.biz.system.oauth2.OAuth2TokenCommonApi;
import cn.iocoder.yudao.framework.security.config.SecurityProperties;
import cn.iocoder.yudao.framework.security.core.filter.TokenAuthenticationFilter;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.security.TenantSecurityWebFilter;
import cn.iocoder.yudao.framework.tenant.core.service.TenantFrameworkService;
import cn.iocoder.yudao.framework.tenant.core.web.TenantContextWebFilter;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.system.controller.admin.tenant.TenantController;
import jakarta.servlet.Filter;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Opt-in browser -> loopback Tomcat -> actual MGS services on isolated H2/jedis-mock.
 * KnowDo, contact verification and dictionary remain explicit fixtures. Never a production or WeChat test. */
@EnabledIfEnvironmentVariable(named = "TRIAL_UI_URL", matches = "http://127\\.0\\.0\\.1:[0-9]+")
class TrialBrowserJourneyTest {
    @TempDir Path temporary;

    @Test void actualBrowserLoginReadsAgentWrittenRecordsWithAccountIsolation() throws Exception {
        var server = new Tomcat(); server.setBaseDir(temporary.resolve("tomcat").toString()); server.setPort(0);
        server.getConnector().setProperty("address", "127.0.0.1");
        var servlet = server.addContext("", temporary.toString());
        servlet.setParentClassLoader(getClass().getClassLoader());
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("isolated-browser",
                    Map.of("mgs.trial.storage-enabled", "true", "yudao.captcha.enable", "false")));
            context.setServletContext(servlet.getServletContext()); context.register(Configuration.class); context.refresh();
            var fixture = new TrialLocalJourneyIntegrationTest(); context.getAutowireCapableBeanFactory().autowireBean(fixture);
            fixture.setup();
            // Exercise the actual migration's page/button hierarchy rather than the unit fixture's two page rows.
            fixture.jdbc.update("DELETE FROM system_menu");
            String menus = Files.readString(Path.of("../script/trial/V20260914_02__trial_menus.sql"))
                    .replace("START TRANSACTION;", "").replace("COMMIT;", "");
            new ResourceDatabasePopulator(new ByteArrayResource(menus.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .execute(fixture.jdbc.getDataSource());
            var accounts = new ArrayList<Map<String, Object>>();
            for (String subject : new String[]{"browser-first", "browser-second"}) {
                String id = fixture.apply(subject); fixture.ready(id);
                var credentials = fixture.delivery.claim(id, fixture.identity(subject));
                accounts.add(Map.of("username", credentials.username(), "password", credentials.password(),
                        "tenantId", credentials.tenantId(), "agentToken", fixture.authorizations.exchange(id, fixture.identity(subject)).accessToken(),
                        "customerId", Long.parseLong(fixture.store.step(id, "DEMO").result().get("customerId"))));
            }
            var dispatcher = Tomcat.addServlet(servlet, "dispatcher", new DispatcherServlet(context)); dispatcher.setLoadOnStartup(1);
            servlet.addServletMappingDecoded("/", "dispatcher");
            filter(servlet, "tenant-context", new TenantContextWebFilter());
            filter(servlet, "security", context.getBean("springSecurityFilterChain", Filter.class));
            server.start();
            Path privateInput = temporary.resolve("browser-input.json");
            Files.createFile(privateInput, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(privateInput, fixture.json.writeValueAsString(Map.of("backendUrl", "http://127.0.0.1:" + server.getConnector().getLocalPort(), "accounts", accounts)));
            var process = new ProcessBuilder("node", "../script/trial/browser-live-journey.mjs", privateInput.toString())
                    .redirectErrorStream(true).redirectOutput(temporary.resolve("browser.log").toFile()).start();
            try {
                assertTrue(process.waitFor(240, TimeUnit.SECONDS), "Isolated browser journey timed out");
                // Browser script prints only sanitized status/errors, never fixture credentials.
                assertEquals(0, process.exitValue(), () -> {
                    try { return Files.readString(temporary.resolve("browser.log")); }
                    catch (Exception ignored) { return "Unable to read browser test log"; }
                });
            } finally {
                if (process.isAlive()) { process.destroyForcibly(); process.waitFor(15, TimeUnit.SECONDS); }
                Files.deleteIfExists(privateInput);
            }
            assertEquals(1, fixture.count("crm_follow_up_record"));
            assertEquals(1, fixture.count("crm_trial_business_operation"));
            assertEquals(2, fixture.count("crm_customer"));
            assertEquals(2, fixture.count("crm_clue"));
        } finally {
            server.stop(); server.destroy();
        }
    }
    private static void filter(org.apache.catalina.Context context, String name, Filter filter) {
        var definition = new FilterDef(); definition.setFilterName(name); definition.setFilter(filter); context.addFilterDef(definition);
        var mapping = new FilterMap(); mapping.setFilterName(name); mapping.addURLPattern("/*"); context.addFilterMap(mapping);
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.cache.annotation.EnableCaching
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @org.springframework.transaction.annotation.EnableTransactionManagement
    @Import(TenantController.class)
    static class Configuration extends TrialLocalJourneyConfiguration {
        @Bean @Override com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json()
                    .modulesToInstall(new cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration().timestampSupportModuleBean())
                    .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        }
        @Override public void configureMessageConverters(java.util.List<org.springframework.http.converter.HttpMessageConverter<?>> converters) {
            converters.add(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(objectMapper()));
        }
        @Override public void configurePathMatch(PathMatchConfigurer configurer) {
            configurer.addPathPrefix("/admin-api", type -> type == TenantController.class
                    || type == cn.iocoder.yudao.module.system.controller.admin.auth.AuthController.class
                    || type == cn.iocoder.yudao.module.crm.controller.admin.trial.TrialBusinessController.class);
        }
        @Bean @Override SecurityFilterChain security(HttpSecurity http, GlobalExceptionHandler errors, OAuth2TokenCommonApi tokens) throws Exception {
            Set<String> publicPaths = Set.of("/admin-api/system/auth/login", "/admin-api/system/tenant/simple-list", "/admin-api/system/tenant/get-by-website");
            new cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils(new WebProperties());
            var token = new TokenAuthenticationFilter(new SecurityProperties(), errors, tokens);
            var tenant = new TenantSecurityWebFilter(new WebProperties(), new TenantProperties(), publicPaths, errors, mock(TenantFrameworkService.class));
            return http.csrf(c -> c.disable()).sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(c -> c.requestMatchers(publicPaths.toArray(String[]::new)).permitAll().anyRequest().authenticated())
                    .exceptionHandling(c -> c.authenticationEntryPoint((req, res, ex) -> res.setStatus(401)))
                    .addFilterBefore(token, UsernamePasswordAuthenticationFilter.class)
                    .addFilterAfter(tenant, TokenAuthenticationFilter.class).build();
        }
    }
}
