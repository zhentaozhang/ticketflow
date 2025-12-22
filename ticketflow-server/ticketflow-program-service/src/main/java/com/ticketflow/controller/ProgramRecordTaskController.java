package com.ticketflow.controller;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.ProgramRecordTaskAddDto;
import com.ticketflow.dto.ProgramRecordTaskListDto;
import com.ticketflow.dto.ProgramRecordTaskUpdateDto;
import com.ticketflow.service.ProgramRecordTaskService;
import com.ticketflow.vo.ProgramRecordTaskVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 节目对账任务 API——运营端对账任务管理
 */
@RestController
@RequestMapping("/program/record/task")
@Tag(name = "program-record-task", description = "节目对账记录任务")
public class ProgramRecordTaskController {
    
    @Autowired
    private ProgramRecordTaskService programRecordTaskService;
    
    
    @Operation(summary  = "获取节目对账记录任务集合")
    @PostMapping(value = "/select")
    public ApiResponse<List<ProgramRecordTaskVo>> select(@Valid @RequestBody ProgramRecordTaskListDto programRecordTaskListDto) {
        return ApiResponse.ok(programRecordTaskService.select(programRecordTaskListDto));
    }
    
    @Operation(summary  = "修改节目对账记录任务集合")
    @PostMapping(value = "/update")
    public ApiResponse<Integer> update(@Valid @RequestBody ProgramRecordTaskUpdateDto programRecordTaskUpdateDto) {
        return ApiResponse.ok(programRecordTaskService.updateByCreateTime(programRecordTaskUpdateDto));
    }
    
    @Operation(summary  = "添加节目对账记录任务")
    @PostMapping(value = "/add")
    public ApiResponse<Integer> add(@Valid @RequestBody ProgramRecordTaskAddDto orderTicketUserRecordAddDto) {
        return ApiResponse.ok(programRecordTaskService.add(orderTicketUserRecordAddDto));
    }
}
