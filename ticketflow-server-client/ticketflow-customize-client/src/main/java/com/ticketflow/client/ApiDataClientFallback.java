package com.ticketflow.client;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.AddApiDataDto;
import com.ticketflow.dto.InsertMessageConsumerRecordDto;
import com.ticketflow.dto.InsertMessageProducerRecordDto;
import com.ticketflow.dto.MessageIdDto;
import com.ticketflow.dto.UpdateMessageConsumerRecordDto;
import com.ticketflow.dto.UpdateMessageProducerRecordDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.vo.MessageConsumerRecordVo;
import com.ticketflow.vo.MessageProducerRecordVo;
import org.springframework.stereotype.Component;

/**
 * 定制数据服务Feign降级。定制服务不可用时的降级处理。
 */
@Component
public class ApiDataClientFallback implements ApiDataClient {

    @Override
    public ApiResponse<Boolean> add(final AddApiDataDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);  // 监控数据丢失，不影响主流程
    }

    @Override
    public ApiResponse<MessageProducerRecordVo> insertMessageProducerRecord(final InsertMessageProducerRecordDto insertMessageProducerRecordDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);  // 消息记录失败 → 重试或回滚
    }

    @Override
    public ApiResponse<Boolean> updateMessageProducerRecord(final UpdateMessageProducerRecordDto updateMessageProducerRecordDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }

    @Override
    public ApiResponse<MessageConsumerRecordVo> getMessageConsumerByMessageId(final MessageIdDto messageIdDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }

    @Override
    public ApiResponse<MessageConsumerRecordVo> insertMessageConsumerRecord(final InsertMessageConsumerRecordDto insertMessageConsumerRecordDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }

    @Override
    public ApiResponse<Boolean> updateMessageConsumerRecord(final UpdateMessageConsumerRecordDto updateMessageConsumerRecordDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
}
