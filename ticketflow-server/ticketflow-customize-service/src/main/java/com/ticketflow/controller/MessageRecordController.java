package com.ticketflow.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.ExecuteExceptionMessageDto;
import com.ticketflow.dto.MessageRecordDto;
import com.ticketflow.service.MessageRecordService;
import com.ticketflow.vo.MessageRecordVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息记录 API——消息发送/消费记录查询与异常重试
 */
@RestController
@RequestMapping("/message/record")
@Tag(name = "/message/record", description = "消息记录")
public class MessageRecordController {

    @Autowired
    private MessageRecordService messageRecordService;
    
    @Operation(summary  = "分页查询消息记录")
    @PostMapping(value = "/page")
    public ApiResponse<IPage<MessageRecordVo>> page(@Valid @RequestBody MessageRecordDto messageRecordDto) {
        return ApiResponse.ok(messageRecordService.page(messageRecordDto));
    }
    
    @Operation(summary  = "执行对账任务")
    @PostMapping(value = "/execute/Reconciliation/task")
    public ApiResponse<Boolean> executeReconciliationTask() {
        return ApiResponse.ok(messageRecordService.executeReconciliationTask());
    }
    
    @Operation(summary  = "处理异常消息")
    @PostMapping(value = "/execute/exception/message")
    public ApiResponse<Boolean> executeExceptionMessage(@Valid @RequestBody ExecuteExceptionMessageDto executeExceptionMessageDto) {
        return ApiResponse.ok(messageRecordService.executeExceptionMessage(executeExceptionMessageDto));
    }
}
