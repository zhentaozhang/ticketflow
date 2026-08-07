package com.ticketflow.client;

import com.ticketflow.controller.ProgramRecordTaskController;
import com.ticketflow.dto.ProgramRecordTaskAddDto;
import com.ticketflow.vo.ProgramVo;
import feign.MethodMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ProgramClient Feign 契约回归测试。
 * 验证客户端 Feign 路径与服务端 Controller 实际映射一致（防止路径不一致导致请求 404），
 * 以及 ProgramVo 字段类型与实体/文档一致（防止类型不匹配导致数据损坏）。
 */
class ProgramClientContractTest {

    private final SpringMvcContract contract = new SpringMvcContract();

    @Test
    void addMappingMatchesServerController() throws NoSuchMethodException {
        String clientPath = contract.parseAndValidateMetadata(ProgramClient.class,
                ProgramClient.class.getMethod("add", ProgramRecordTaskAddDto.class)).template().path();
        assertEquals(serverMapping(ProgramRecordTaskController.class, "add"), clientPath);
    }

    @Test
    void issueTimeIsDate() throws NoSuchFieldException {
        assertEquals(Date.class, ProgramVo.class.getDeclaredField("issueTime").getType());
    }

    private static String serverMapping(Class<?> controller, String methodName) throws NoSuchMethodException {
        String classPath = controller.getAnnotation(RequestMapping.class).value()[0];
        for (Method method : controller.getDeclaredMethods()) {
            if (method.getName().equals(methodName) && method.isAnnotationPresent(PostMapping.class)) {
                return classPath + method.getAnnotation(PostMapping.class).value()[0];
            }
        }
        throw new NoSuchMethodException(methodName);
    }
}
