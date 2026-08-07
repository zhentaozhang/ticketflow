package com.ticketflow.client;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.AddApiDataDto;
import com.ticketflow.dto.InsertMessageConsumerRecordDto;
import com.ticketflow.dto.InsertMessageProducerRecordDto;
import com.ticketflow.dto.MessageIdDto;
import com.ticketflow.dto.UpdateMessageConsumerRecordDto;
import com.ticketflow.dto.UpdateMessageProducerRecordDto;
import com.ticketflow.vo.MessageConsumerRecordVo;
import com.ticketflow.vo.MessageProducerRecordVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;

import static com.ticketflow.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;

/**
 * API 数据服务 Feign 客户端。
 * order-service 通过此接口记录/查询消息生产与消费记录，
 * 实现延迟消息的幂等处理
 */
@Component
@FeignClient(value = SPRING_INJECT_PREFIX_DISTINCTION_NAME + "-" + "customize-service")
//                ↑ Nacos 服务名  ↑ order-service 消息幂等记录用
public interface ApiDataClient {

    /**
     * 记录API调用数据（gateway 监控用）
     */
    @PostMapping(value = "/apiData/add")
    ApiResponse<Boolean> add(AddApiDataDto dto);

    /**
     * 插入消息发送记录（可靠消息——先记录后发送）
     */
    @PostMapping(value = "/message/producer/record/insert")
    ApiResponse<MessageProducerRecordVo> insertMessageProducerRecord(InsertMessageProducerRecordDto insertMessageProducerRecordDto);

    /**
     * 更新消息发送状态（已发送/失败）
     */
    @PostMapping(value = "/message/producer/record/update")
    ApiResponse<Boolean> updateMessageProducerRecord(UpdateMessageProducerRecordDto updateMessageProducerRecordDto);

    /**
     * 查询消息消费记录（幂等判断）
     */
    @PostMapping(value = "/message/consumer/record/getByMessageId")
    ApiResponse<MessageConsumerRecordVo> getMessageConsumerByMessageId(MessageIdDto messageIdDto);

    /**
     * 插入消息消费记录
     */
    @PostMapping(value = "/message/consumer/record/insert")
    ApiResponse<MessageConsumerRecordVo> insertMessageConsumerRecord(InsertMessageConsumerRecordDto insertMessageConsumerRecordDto);

    /**
     * 更新消息消费状态
     */
    @PostMapping(value = "/message/consumer/record/update")
    ApiResponse<Boolean> updateMessageConsumerRecord(UpdateMessageConsumerRecordDto updateMessageConsumerRecordDto);
}
