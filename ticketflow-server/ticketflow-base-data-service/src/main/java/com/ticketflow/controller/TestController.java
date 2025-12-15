package com.ticketflow.controller;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.ChannelDataAddDto;
import com.ticketflow.service.ChannelDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试 API——基础数据服务健康检查
 */
@RestController
@RequestMapping("/test")
@Tag(name = "test-data", description = "测试")
public class TestController {
    
    @Autowired
    private ChannelDataService channelDataService;
    
    @Operation(summary = "测试")
    @PostMapping(value = "/test")
    public ApiResponse<Boolean> test(@Valid @RequestBody ChannelDataAddDto channelDataAddDto) {
        channelDataService.test(channelDataAddDto);
        return ApiResponse.ok(true);
    }
}
