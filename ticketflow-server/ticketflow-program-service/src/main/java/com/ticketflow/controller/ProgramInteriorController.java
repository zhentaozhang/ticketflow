package com.ticketflow.controller;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.ProgramOperateDataDto;
import com.ticketflow.dto.ReduceRemainNumberDto;
import com.ticketflow.service.ProgramService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 节目内部 RPC API——仅限服务间 Feign 调用，查询节目/座位/票档数据
 */
@RestController
@RequestMapping("/program/interior")
public class ProgramInteriorController {
    
    @Autowired
    private ProgramService programService;
    
    
    @Operation(summary  = "扣减库存相关操作")
    @PostMapping(value = "/reduce/remain/number")
    public ApiResponse<Boolean> operateSeatLockAndTicketCategoryRemainNumber(@Valid @RequestBody ReduceRemainNumberDto reduceRemainNumberDto) {
        return ApiResponse.ok(programService.operateSeatLockAndTicketCategoryRemainNumber(reduceRemainNumberDto));
    }
    
    @Operation(summary  = "订单支付成功或者取消订单后对节目服务库的相关操作")
    @PostMapping(value = "/operate/program/data")
    public ApiResponse<Boolean> operateProgramData(@Valid @RequestBody ProgramOperateDataDto programOperateDataDto){
        return ApiResponse.ok(programService.operateProgramData(programOperateDataDto));
    }
}
