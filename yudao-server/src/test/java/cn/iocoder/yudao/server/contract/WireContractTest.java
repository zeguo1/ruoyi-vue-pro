package cn.iocoder.yudao.server.contract;

import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.erp.controller.admin.sale.ErpSaleOrderController;
import cn.iocoder.yudao.module.erp.service.sale.ErpSaleOrderService;
import cn.iocoder.yudao.module.infra.controller.admin.file.FileController;
import cn.iocoder.yudao.module.infra.service.file.FileService;
import cn.iocoder.yudao.module.system.controller.admin.dept.DeptController;
import cn.iocoder.yudao.module.system.service.dept.DeptService;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual application Jackson configuration; mock business services only, no storage/network/DB writes. */
class WireContractTest {
    static ObjectMapper mapper() {
        var builder = Jackson2ObjectMapperBuilder.json();
        var config = new YudaoJacksonAutoConfiguration();
        config.ldtEpochMillisCustomizer().customize(builder);
        builder.modulesToInstall(config.timestampSupportModuleBean());
        builder.featuresToEnable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        builder.featuresToDisable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS);
        return builder.build();
    }
    static class Dates {
        public LocalDateTime time;
        @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") public LocalDateTime formatted;
        @JsonFormat(pattern="yyyy-MM-dd") public LocalDateTime dateOnly;
        public LocalDate date;
        public LocalTime clock;
        public Long id;
    }
    private MockMvc mvc(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper()))
                .setControllerAdvice(new GlobalExceptionHandler("isolated", mock(ApiErrorLogCommonApi.class))).build();
    }
    @Test void actualDateAndLongWireRepresentationsArePreserved() throws Exception {
        var mapper = mapper();
        var dates = mapper.readValue("{\"time\":\"2026-09-14T10:20:30\",\"formatted\":\"2026-09-14 10:20:30\",\"date\":\"2026-09-14\",\"clock\":\"10:20:30\",\"id\":9007199254740991}", Dates.class);
        var json = mapper.valueToTree(dates);
        assertTrue(json.path("time").isIntegralNumber());
        assertEquals("2026-09-14 10:20:30", json.path("formatted").asText());
        assertEquals(mapper.readTree("[2026,9,14]"), json.path("date"));
        assertEquals(mapper.readTree("[10,20,30]"), json.path("clock"));
        dates.clock = LocalTime.of(10,20,30,123000000);
        assertEquals(mapper.readTree("[10,20,30,123]"), mapper.valueToTree(dates).path("clock"));
        assertEquals(dates.clock, mapper.readValue("{\"clock\":[10,20,30,123000000]}", Dates.class).clock);
        assertTrue(json.path("id").isTextual());
        dates.id = 9007199254740990L;
        assertTrue(mapper.valueToTree(dates).path("id").isIntegralNumber());
        assertEquals(dates.time, mapper.readValue("{\"time\":" + json.path("time") + "}", Dates.class).time);
        assertEquals(dates.time, mapper.readValue("{\"time\":\"" + json.path("time") + "\"}", Dates.class).time);
        assertEquals(OffsetDateTime.parse("2026-09-14T10:20:30+08:00").atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(),
                mapper.readValue("{\"time\":\"2026-09-14T10:20:30+08:00\"}", Dates.class).time);
        assertEquals(LocalDateTime.of(2026,9,14,0,0), mapper.readValue("{\"dateOnly\":\"2026-09-14\"}", Dates.class).dateOnly);
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> mapper.readValue("{\"formatted\":\"2026-02-30 10:20:30\"}", Dates.class));
        for (String invalid : List.of("\"nonsense\"", "true", "1.5", "[]", "\"2026-99-99T10:00:00\""))
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class, () -> mapper.readValue("{\"time\":" + invalid + "}", Dates.class));
    }
    @Test void badDateAndWrongItemCannotReachSalesService() throws Exception {
        var controller = new ErpSaleOrderController(); var service = mock(ErpSaleOrderService.class);
        ReflectionTestUtils.setField(controller, "saleOrderService", service);
        var mvc = mvc(controller);
        String valid = "{\"customerId\":101,\"orderTime\":\"2026-09-14T10:20:30\",\"items\":[{\"productId\":201,\"count\":2.5,\"productPrice\":100}]}";
        var wrong = mvc.perform(post("/erp/sale-order/create").contentType("application/json")
                .content(valid.replace("{\"productId\":201,\"count\":2.5,\"productPrice\":100}", "{\"id\":1,\"sort\":1}")))
                .andExpect(jsonPath("code").value(400)).andExpect(jsonPath("msg").value(org.hamcrest.Matchers.containsString("items[0].")))
                .andReturn();
        mvc.perform(post("/erp/sale-order/create").contentType("application/json").content(valid.replace("2026-09-14T10:20:30", "invalid-date")))
                .andExpect(jsonPath("code").value(400)).andExpect(jsonPath("msg").value(org.hamcrest.Matchers.containsString("orderTime")));
        mvc.perform(post("/erp/sale-order/create").contentType("application/json").content(valid.replace("\"count\":2.5", "\"count\":\"invalid-number\"")))
                .andExpect(jsonPath("code").value(400)).andExpect(jsonPath("msg").value(org.hamcrest.Matchers.containsString("items[0].count")));
        mvc.perform(post("/erp/sale-order/create").contentType("application/json").content("{"))
                .andExpect(jsonPath("code").value(400));
        verifyNoInteractions(service);
        when(service.createSaleOrder(any())).thenReturn(901L);
        mvc.perform(post("/erp/sale-order/create").contentType("application/json").content(valid))
                .andExpect(jsonPath("code").value(0)).andExpect(jsonPath("data").value(901));
        verify(service).createSaleOrder(argThat(r -> r.getItems().get(0).getProductUnitId() == null && r.getOrderTime().getYear() == 2026));
        Files.writeString(Path.of("target/v6-invalid-order-response.json"), wrong.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void erpStatusEnumIsEnforcedBeforeServiceByMethodValidation() throws Exception {
        var controller = new ErpSaleOrderController(); var service = mock(ErpSaleOrderService.class);
        ReflectionTestUtils.setField(controller, "saleOrderService", service);
        var validation = new org.springframework.validation.beanvalidation.MethodValidationPostProcessor();
        validation.setProxyTargetClass(true); validation.afterPropertiesSet();
        Object proxy = validation.postProcessAfterInitialization(controller, "saleOrderController");
        var mvc = mvc(proxy);
        mvc.perform(put("/erp/sale-order/update-status").param("id", "101").param("status", "99"))
                .andExpect(jsonPath("code").value(400));
        verifyNoInteractions(service);
        mvc.perform(put("/erp/sale-order/update-status").param("id", "101").param("status", "20"))
                .andExpect(jsonPath("code").value(0));
        verify(service).updateSaleOrderStatus(101L, 20);
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var rows = mapper().readTree(Files.readString(Path.of("../script/openapi/v6-parameter-corrections.json")));
            int checked = 0;
            for (var row : rows) {
                if (!"status".equals(row.path("parameter").asText())) continue;
                String[] handler = row.path("handler").asText().split("#");
                Class<?> type = Class.forName(handler[0]); Object instance = new org.objenesis.ObjenesisStd().newInstance(type);
                var method = type.getMethod(handler[1], Long.class, Integer.class);
                assertFalse(factory.getValidator().forExecutables().validateParameters(instance, method, new Object[]{101L,99}).isEmpty());
                assertTrue(factory.getValidator().forExecutables().validateParameters(instance, method, new Object[]{101L,10}).isEmpty());
                checked++;
            }
            assertEquals(12, checked);
        }
    }
    @Test void multipartFileAndDirectoryBindToActualUploadDto() throws Exception {
        var controller = new FileController(); var service = mock(FileService.class);
        ReflectionTestUtils.setField(controller, "fileService", service);
        when(service.createFile(any(byte[].class), anyString(), anyString(), anyString())).thenReturn("https://example.invalid/isolated.txt");
        var mvc = mvc(controller);
        mvc.perform(multipart("/infra/file/upload").param("directory", "isolated"))
                .andExpect(jsonPath("code").value(400));
        verifyNoInteractions(service);
        mvc.perform(multipart("/infra/file/upload").file(new MockMultipartFile("file", "isolated.txt", "text/plain", new byte[]{1,2}))
                .param("directory", "isolated")).andExpect(jsonPath("code").value(0));
        verify(service).createFile(new byte[]{1,2}, "isolated.txt", "isolated", "text/plain");
    }
    @Test void emptyAndNullDataAreSuccessfulButHttp200BusinessFailureIsNot() throws Exception {
        var controller = new DeptController(); var service = mock(DeptService.class);
        ReflectionTestUtils.setField(controller, "deptService", service);
        var mvc = mvc(controller);
        when(service.getDeptList(any(cn.iocoder.yudao.module.system.controller.admin.dept.vo.dept.DeptListReqVO.class))).thenReturn(List.of());
        var empty = mvc.perform(get("/system/dept/list")).andExpect(jsonPath("code").value(0)).andExpect(jsonPath("data").isEmpty()).andReturn();
        var absent = mvc.perform(get("/system/dept/get").param("id", "101")).andExpect(jsonPath("code").value(0)).andReturn();
        assertTrue(mapper().readTree(absent.getResponse().getContentAsString()).path("data").isNull());
        when(service.getDept(101L)).thenThrow(new ServiceException(1002004001, "隔离测试业务失败"));
        var failed = mvc.perform(get("/system/dept/get").param("id", "101"))
                .andExpect(status().isOk()).andExpect(jsonPath("code").value(1002004001)).andReturn();
        Files.writeString(Path.of("target/v6-response-examples.json"), mapper().writeValueAsString(Map.of(
                "emptyList", mapper().readTree(empty.getResponse().getContentAsString()),
                "nullResult", mapper().readTree(absent.getResponse().getContentAsString()),
                "businessFailure", mapper().readTree(failed.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)))));
    }
}
