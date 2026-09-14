package cn.iocoder.yudao.server.contract;

import cn.iocoder.yudao.framework.common.validation.Update;
import cn.iocoder.yudao.module.system.controller.admin.captcha.CaptchaController;
import cn.iocoder.yudao.module.system.controller.admin.dept.DeptController;
import cn.iocoder.yudao.module.system.controller.admin.permission.PermissionController;
import cn.iocoder.yudao.module.system.service.dept.DeptService;
import cn.iocoder.yudao.module.system.service.permission.PermissionService;
import cn.iocoder.yudao.module.system.service.tenant.TenantService;
import cn.iocoder.yudao.module.system.controller.admin.permission.vo.permission.*;
import com.anji.captcha.service.CaptchaService;
import com.anji.captcha.model.common.ResponseModel;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.annotation.Validated;
import java.nio.file.*;
import java.util.*;
import cn.iocoder.yudao.module.system.service.tenant.handler.TenantMenuHandler;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Isolated controller/validator checks. No database, Redis, authentication or external calls. */
class BackendHandoffContractTest {
    @Test void allNewUpdateContractsRequireExistingIdWithoutChangingCreateGroups() throws Exception {
        var rows = io.swagger.v3.core.util.Json.mapper().readTree(Files.readString(Path.of("../script/openapi/v5-update-contracts.json")));
        assertEquals(192, rows.size());
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (var row : rows) {
                String model = row.path("model").asText().split("/src/main/java/")[1].replace('/', '.').replace(".java", "");
                var type = Class.forName(model);
                assertTrue(validator.validateValue(type, "id", null).isEmpty(), model + " create id");
                assertFalse(validator.validateValue(type, "id", null, Update.class).isEmpty(), model + " update id");
                var id = type.getDeclaredField("id").getType() == String.class ? "existing-model" : 101L;
                assertTrue(validator.validateValue(type, "id", id, Update.class).isEmpty(), model);
                String controller = row.path("controller").asText().split("/src/main/java/")[1].replace('/', '.').replace(".java", "");
                var method = Arrays.stream(Class.forName(controller).getDeclaredMethods())
                        .filter(m -> m.getName().equals(row.path("controllerMethod").asText())).findFirst().orElseThrow();
                var parameter = Arrays.stream(method.getParameters()).filter(p -> p.getType() == type).findFirst().orElseThrow();
                assertTrue(Arrays.asList(parameter.getAnnotation(Validated.class).value()).contains(Update.class), controller);
            }
            var salary = Class.forName("cn.iocoder.yudao.module.hrm.controller.admin.salary.vo.employeeinfo.HrmSalaryEmployeeInfoUpdateReqVO");
            assertTrue(validator.validateValue(salary, "id", null).isEmpty(), "Salary creation/update remains an explicit optional-id exception");
        }
    }

    @Test void updatingParentCanStillAddChildrenWithoutIds() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            for (String scene : List.of("Inner", "Normal")) {
                var type = Class.forName("cn.iocoder.yudao.module.infra.controller.admin.demo.demo03." + scene.toLowerCase()
                        + ".vo.Demo03Student" + scene + "SaveReqVO");
                var parent = type.getConstructor().newInstance();
                ReflectionTestUtils.setField(parent, "id", 101L);
                ReflectionTestUtils.setField(parent, "name", "isolated");
                ReflectionTestUtils.setField(parent, "sex", 1);
                ReflectionTestUtils.setField(parent, "birthday", java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
                ReflectionTestUtils.setField(parent, "description", "isolated");
                ReflectionTestUtils.setField(parent, "demo03Courses", List.of(new cn.iocoder.yudao.module.infra.dal.dataobject.demo.demo03.Demo03CourseDO()));
                ReflectionTestUtils.setField(parent, "demo03Grade", new cn.iocoder.yudao.module.infra.dal.dataobject.demo.demo03.Demo03GradeDO());
                assertTrue(factory.getValidator().validate(parent, Update.class).isEmpty(), scene + " new child ids must remain optional");
            }
        }
    }

    @Test void systemCreateIdsAreGeneratedInsteadOfCopiedFromCaller() {
        var dept = new cn.iocoder.yudao.module.system.service.dept.DeptServiceImpl();
        var deptMapper = mock(cn.iocoder.yudao.module.system.dal.mysql.dept.DeptMapper.class);
        ReflectionTestUtils.setField(dept, "deptMapper", deptMapper);
        doAnswer(inv -> { var row = inv.<cn.iocoder.yudao.module.system.dal.dataobject.dept.DeptDO>getArgument(0); assertNull(row.getId()); row.setId(201L); return 1; })
                .when(deptMapper).insert(any(cn.iocoder.yudao.module.system.dal.dataobject.dept.DeptDO.class));
        var req = new cn.iocoder.yudao.module.system.controller.admin.dept.vo.dept.DeptSaveReqVO(); req.setId(999L); req.setName("isolated");
        assertEquals(201L, dept.createDept(req));
        var typeService = new cn.iocoder.yudao.module.system.service.dict.DictTypeServiceImpl();
        var typeMapper = mock(cn.iocoder.yudao.module.system.dal.mysql.dict.DictTypeMapper.class);
        ReflectionTestUtils.setField(typeService, "dictTypeMapper", typeMapper);
        doAnswer(inv -> { var row = inv.<cn.iocoder.yudao.module.system.dal.dataobject.dict.DictTypeDO>getArgument(0); assertNull(row.getId()); row.setId(202L); return 1; })
                .when(typeMapper).insert(any(cn.iocoder.yudao.module.system.dal.dataobject.dict.DictTypeDO.class));
        var typeReq = new cn.iocoder.yudao.module.system.controller.admin.dict.vo.type.DictTypeSaveReqVO(); typeReq.setId(999L); typeReq.setName("isolated"); typeReq.setType("isolated");
        assertEquals(202L, typeService.createDictType(typeReq));
        var dataService = new cn.iocoder.yudao.module.system.service.dict.DictDataServiceImpl();
        var dataMapper = mock(cn.iocoder.yudao.module.system.dal.mysql.dict.DictDataMapper.class);
        var types = mock(cn.iocoder.yudao.module.system.service.dict.DictTypeService.class);
        ReflectionTestUtils.setField(dataService, "dictDataMapper", dataMapper); ReflectionTestUtils.setField(dataService, "dictTypeService", types);
        var validType = new cn.iocoder.yudao.module.system.dal.dataobject.dict.DictTypeDO(); validType.setStatus(0);
        when(types.getDictType("isolated")).thenReturn(validType);
        doAnswer(inv -> { var row = inv.<cn.iocoder.yudao.module.system.dal.dataobject.dict.DictDataDO>getArgument(0); assertNull(row.getId()); row.setId(203L); return 1; })
                .when(dataMapper).insert(any(cn.iocoder.yudao.module.system.dal.dataobject.dict.DictDataDO.class));
        var dataReq = new cn.iocoder.yudao.module.system.controller.admin.dict.vo.data.DictDataSaveReqVO(); dataReq.setId(999L); dataReq.setDictType("isolated"); dataReq.setValue("isolated");
        assertEquals(203L, dataService.createDictData(dataReq));
    }

    @Test void missingUpdateIdIsRejectedBeforeCallingBusinessService() throws Exception {
        var service = mock(DeptService.class); var controller = new DeptController();
        ReflectionTestUtils.setField(controller, "deptService", service);
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler(
                "isolated", mock(cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi.class))).build();
        String body = "{\"name\":\"isolated\",\"sort\":0,\"status\":0}";
        mvc.perform(put("/system/dept/update").contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("code").value(400));
        verifyNoInteractions(service);
        mvc.perform(put("/system/dept/update").contentType("application/json").content(body.replace("{", "{\"id\":101,")))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"));
        verify(service).updateDept(argThat(r -> r.getId().equals(101L)));
    }

    @Test void captchaStagesRejectIncompleteInputsBeforeSdkAndPreserveResponseFormat() throws Exception {
        var service = mock(CaptchaService.class); var controller = new CaptchaController();
        ReflectionTestUtils.setField(controller, "captchaService", service);
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler(
                "isolated", mock(cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi.class))).build();
        mvc.perform(post("/system/captcha/check").contentType("application/json").content("{\"captchaType\":\"blockPuzzle\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("repCode").value("0016"))
                .andExpect(jsonPath("repMsg").value(org.hamcrest.Matchers.containsString("pointJson")))
                .andExpect(jsonPath("repMsg").value(org.hamcrest.Matchers.containsString("token")));
        mvc.perform(post("/system/captcha/get").contentType("application/json").content("{\"captchaType\":\"unknown\"}"))
                .andExpect(jsonPath("repCode").value("0016"));
        verifyNoInteractions(service);
        when(service.get(any())).thenReturn(ResponseModel.success());
        mvc.perform(post("/system/captcha/get").contentType("application/json").content("{\"captchaType\":\"blockPuzzle\"}"))
                .andExpect(jsonPath("repCode").value("0000")).andExpect(content().contentTypeCompatibleWith("application/json"));
        when(service.check(any())).thenReturn(ResponseModel.success());
        mvc.perform(post("/system/captcha/check").contentType("application/json").content("{\"captchaType\":\"clickWord\",\"token\":\"isolated-token\",\"pointJson\":\"isolated-encoded-coordinates\"}"))
                .andExpect(jsonPath("repCode").value("0000"));
        verify(service).check(argThat(r -> "isolated-token".equals(r.getToken()) && r.getCaptchaVerification() == null));
    }

    @Test void omittedNullAndEmptyMenuSetsAllReachClearWithoutNullPointer() throws Exception {
        var controller = new PermissionController(); var service = mock(PermissionService.class); var tenant = mock(TenantService.class);
        ReflectionTestUtils.setField(controller, "permissionService", service); ReflectionTestUtils.setField(controller, "tenantService", tenant);
        doAnswer(inv -> { inv.<TenantMenuHandler>getArgument(0).handle(Set.of(1L,2L)); return null; }).when(tenant).handleTenantMenu(any());
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler(
                "isolated", mock(cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi.class))).build();
        for (String suffix : List.of("", ",\"menuIds\":null", ",\"menuIds\":[]")) {
            mvc.perform(post("/system/permission/assign-role-menu").contentType("application/json").content("{\"roleId\":101"+suffix+"}"))
                    .andExpect(status().isOk());
        }
        verify(service, times(3)).assignRoleMenu(101L, Set.of());
        mvc.perform(post("/system/permission/assign-role-menu").contentType("application/json").content("{\"roleId\":101,\"menuIds\":[1,99]}"))
                .andExpect(status().isOk());
        verify(service).assignRoleMenu(101L, Set.of(1L));
        reset(service);
        mvc.perform(post("/system/permission/assign-role-menu").contentType("application/json").content("{\"roleId\":101,\"menuIds\":[null]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("code").value(400));
        verifyNoInteractions(service);
    }
}
