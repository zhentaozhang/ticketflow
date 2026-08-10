package com.ticketflow.controller;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.enums.ProgramOrderVersion;
import com.ticketflow.service.strategy.ProgramOrderContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 下单 API——创建订单/取消订单/获取订单信息
 */
@RestController
@RequestMapping("/program/order")
@Tag(name = "program-order", description = "节目订单")
public class ProgramOrderController {
    
    @Autowired
    private ProgramOrderContext programOrderContext;
    
    @Operation(summary  = "购票V1")
    @PostMapping(value = "/create/v1")
    public ApiResponse<String> createV1(@Valid @RequestBody ProgramOrderCreateDto programOrderCreateDto) {
        return ApiResponse.ok(programOrderContext.get(ProgramOrderVersion.V1_VERSION.getVersion())
                .createOrder(programOrderCreateDto));
    }
    
    @Operation(summary  = "购票V2")
    @PostMapping(value = "/create/v2")
    public ApiResponse<String> createV2(@Valid @RequestBody ProgramOrderCreateDto programOrderCreateDto) {
        return ApiResponse.ok(programOrderContext.get(ProgramOrderVersion.V2_VERSION.getVersion())
                .createOrder(programOrderCreateDto));
    }
    
    @Operation(summary  = "购票V3")
    @PostMapping(value = "/create/v3")
    public ApiResponse<String> createV3(@Valid @RequestBody ProgramOrderCreateDto programOrderCreateDto) {
        return ApiResponse.ok(programOrderContext.get(ProgramOrderVersion.V3_VERSION.getVersion())
                .createOrder(programOrderCreateDto));
    }
    
    @Operation(summary  = "购票V4")
    @PostMapping(value = "/create/v4")
    public ApiResponse<String> createV4(@Valid @RequestBody ProgramOrderCreateDto programOrderCreateDto) {
        return ApiResponse.ok(programOrderContext.get(ProgramOrderVersion.V4_VERSION.getVersion())
                .createOrder(programOrderCreateDto));
    }
}
