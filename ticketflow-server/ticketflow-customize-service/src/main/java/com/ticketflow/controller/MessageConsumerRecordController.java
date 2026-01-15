package com.ticketflow.controller;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.InsertMessageConsumerRecordDto;
import com.ticketflow.dto.MessageIdDto;
import com.ticketflow.dto.UpdateMessageConsumerRecordDto;
import com.ticketflow.service.MessageConsumerRecordService;
import com.ticketflow.vo.MessageConsumerRecordVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息消费记录 API——消费者消息记录查询
 */
@RestController
@RequestMapping("/message/consumer/record")
@Tag(name = "/message/consumer/record", description = "消息消费记录")
public class MessageConsumerRecordController {

    @Autowired
    private MessageConsumerRecordService messageConsumerRecordService;
    
    @Operation(summary  = "查询")
    @PostMapping(value = "/getByMessageId")
    public ApiResponse<MessageConsumerRecordVo> getMessageConsumerByMessageId(@Valid @RequestBody MessageIdDto messageIdDto) {
        return ApiResponse.ok(messageConsumerRecordService.getByMessageId(messageIdDto));
    }
    
    @Operation(summary  = "添加")
    @PostMapping(value = "/insert")
    public ApiResponse<MessageConsumerRecordVo> insertMessageConsumerRecord(@Valid @RequestBody InsertMessageConsumerRecordDto insertMessageConsumerRecordDto) {
        return ApiResponse.ok(messageConsumerRecordService.insertMessageConsumerRecord(insertMessageConsumerRecordDto));
    }
    
    @Operation(summary  = "更新消息消费记录")
    @PostMapping(value = "/update")
    public ApiResponse<Boolean> updateMessageConsumerRecord(@Valid @RequestBody UpdateMessageConsumerRecordDto updateMessageConsumerRecordDto) {
        return ApiResponse.ok(messageConsumerRecordService.updateMessageConsumerRecord(updateMessageConsumerRecordDto));
    }
}
