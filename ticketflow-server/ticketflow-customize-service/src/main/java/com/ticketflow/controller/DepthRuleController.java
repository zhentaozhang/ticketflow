package com.ticketflow.controller;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.DepthRuleDto;
import com.ticketflow.dto.DepthRuleStatusDto;
import com.ticketflow.dto.DepthRuleUpdateDto;
import com.ticketflow.service.DepthRuleService;
import com.ticketflow.vo.DepthRuleVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 深度规则 API——细粒度流量规则管理
 */
@RestController
@RequestMapping("/depthRule")
@Tag(name = "depthRule", description = "深度规则")
public class DepthRuleController {

    @Autowired
    private DepthRuleService depthRuleService;
    
    @Operation(summary  = "添加深度规则")
    @RequestMapping(value = "/add", method = RequestMethod.POST)
    public ApiResponse add(@Valid @RequestBody DepthRuleDto depthRuleDto) {
        depthRuleService.depthRuleAdd(depthRuleDto);
        return ApiResponse.ok();
    }
    
    @Operation(summary  = "修改深度规则")
    @RequestMapping(value = "/update", method = RequestMethod.POST)
    public ApiResponse update(@Valid @RequestBody DepthRuleUpdateDto depthRuleUpdateDto) {
        depthRuleService.depthRuleUpdate(depthRuleUpdateDto);
        return ApiResponse.ok();
    }
    
    @Operation(summary  = "修改深度规则状态")
    @RequestMapping(value = "/updateStatus", method = RequestMethod.POST)
    public ApiResponse updateStatus(@Valid @RequestBody DepthRuleStatusDto depthRuleStatusDto){
        depthRuleService.depthRuleUpdateStatus(depthRuleStatusDto);
        return ApiResponse.ok();
    }
    
    @Operation(summary  = "查询深度规则")
    @RequestMapping(value = "/get", method = RequestMethod.POST)
    public ApiResponse<List<DepthRuleVo>> get(){
        return ApiResponse.ok(depthRuleService.selectList());
    }
}
