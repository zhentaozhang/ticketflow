package com.ticketflow.controller;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.ProgramResetExecuteDto;
import com.ticketflow.service.ProgramService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 节目数据重置 API——开发/测试环境下的数据清理（清空 Redis 缓存、重置座位状态等）
 */
@RestController
@RequestMapping("/program/reset")
@Tag(name = "program-reset", description = "节目数据重置")
public class ProgramResetController {
    
    @Autowired
    private ProgramService programService;
    
    @Operation(summary  = "执行重置(根据节目id)")
    @PostMapping(value = "/execute")
    public ApiResponse<Boolean> resetExecute(@Valid @RequestBody ProgramResetExecuteDto programResetExecuteDto) {
        return ApiResponse.ok(programService.resetExecute(programResetExecuteDto));
    }
}
