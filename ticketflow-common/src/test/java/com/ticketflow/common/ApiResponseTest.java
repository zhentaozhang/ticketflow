package com.ticketflow.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApiResponseTest {

    @Test
    void errorWithCodeAndData_honorsPassedCode() {
        // 修复前该重载硬编码 code=-100，传入 code 被丢弃
        List<String> argumentErrors = List.of("field1 不能为空");

        ApiResponse<List<String>> response = ApiResponse.error(10054, argumentErrors);

        assertEquals(10054, response.getCode());
        assertEquals(argumentErrors, response.getData());
    }

    @Test
    void errorWithCodeAndMessage_honorsPassedCode() {
        ApiResponse<String> response = ApiResponse.error(10001, "用户不存在");

        assertEquals(10001, response.getCode());
        assertEquals("用户不存在", response.getMessage());
    }

    @Test
    void errorWithMessage_only_keepsDefaultMinus100() {
        ApiResponse<String> response = ApiResponse.error("系统异常");

        assertEquals(-100, response.getCode());
        assertEquals("系统异常", response.getMessage());
    }

    @Test
    void errorWithBaseCode_usesEnumCodeAndMsg() {
        ApiResponse<String> response = ApiResponse.error(com.ticketflow.enums.BaseCode.TOKEN_EXPIRE);

        assertEquals(10055, response.getCode());
        assertEquals("token过期", response.getMessage());
    }

    @Test
    void ok_setsCodeZero() {
        ApiResponse<String> response = ApiResponse.ok();

        assertEquals(0, response.getCode());
        assertNull(response.getData());
    }

    @Test
    void okWithData_setsCodeZeroAndCarriesData() {
        String data = "{\"orderNo\":\"1\"}";

        ApiResponse<String> response = ApiResponse.ok(data);

        assertEquals(0, response.getCode());
        assertEquals(data, response.getData());
    }
}
