package com.ticketflow.controller;

import com.alibaba.fastjson.JSON;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.TestDto;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试 API——定制服务健康检查
 */
@RestController
@RequestMapping("/test")
@Slf4j
public class TestController {
    
    @Operation(summary  = "添加普通规则")
    @RequestMapping(value = "/test", method = RequestMethod.POST)
    public ApiResponse<Boolean> test(@Valid @RequestBody TestDto testDto) {
        log.info("dto : {}", JSON.toJSONString(testDto));
        return ApiResponse.ok(true);
    }
}
