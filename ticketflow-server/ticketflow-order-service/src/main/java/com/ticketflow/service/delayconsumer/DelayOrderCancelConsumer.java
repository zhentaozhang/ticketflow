package com.ticketflow.service.delayconsumer;

import com.alibaba.fastjson.JSON;
import com.ticketflow.client.ApiDataClient;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.core.ConsumerTask;
import com.ticketflow.core.SpringUtil;
import com.ticketflow.dto.InsertMessageConsumerRecordDto;
import com.ticketflow.dto.MessageIdDto;
import com.ticketflow.dto.OrderCancelDto;
import com.ticketflow.dto.UpdateMessageConsumerRecordDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.MessageConsumerStatus;
import com.ticketflow.enums.MessageType;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.module.DelayOrderCancelMessageModule;
import com.ticketflow.service.OrderService;
import com.ticketflow.util.DateUtils;
import com.ticketflow.util.StringUtil;
import com.ticketflow.vo.MessageConsumerRecordVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

import static com.ticketflow.service.constant.OrderConstant.DELAY_ORDER_CANCEL_TOPIC;

/**
 * 延迟队列消费者：超时未支付订单自动取消。
 * 订单创建时 DelayOrderCancelSend 推送延迟消息 → 此消费者在超时后消费 →
 * 检查订单支付状态 → 未支付则调用 OrderService.cancel() 取消。
 *
 * 幂等保障：先通过 ApiDataClient 查询消息消费记录，
 *           已消费的不再重复处理
 */
@Slf4j
@Component
public class DelayOrderCancelConsumer implements ConsumerTask {
    
    @Autowired
    private OrderService orderService;
    
    @Autowired
    private ApiDataClient apiDataClient;
    
    
    @Override
    public void execute(String content) {
        log.info("延迟订单取消消息进行消费 content : {}", content);
        if (StringUtil.isEmpty(content)) {
            log.error("延迟队列消息不存在");
            return;
        }
        DelayOrderCancelMessageModule delayOrderCancelMessageModule = JSON.parseObject(content, DelayOrderCancelMessageModule.class);
        
        Long messageTraceId = delayOrderCancelMessageModule.getMessageTraceId();
        Long messageId = delayOrderCancelMessageModule.getMessageId();
        Long programId = delayOrderCancelMessageModule.getProgramId();
        Long orderNumber = delayOrderCancelMessageModule.getOrderNumber();
        
        // 幂等检查：通过 ApiDataClient 查询消息消费记录 → 已成功消费则跳过
        MessageIdDto messageIdDto = new MessageIdDto();
        messageIdDto.setMessageId(messageId);
        MessageConsumerRecordVo existMessageConsumerRecordVo = null;
        try {
            ApiResponse<MessageConsumerRecordVo> apiResponse = apiDataClient.getMessageConsumerByMessageId(messageIdDto);
            if (!apiResponse.getCode().equals(BaseCode.SUCCESS.getCode())) {
                log.error("查询消息消费记录失败 messageId : {}", messageId);
            }else {
                existMessageConsumerRecordVo = apiResponse.getData();
            }
        }catch (Exception e) {
            // api-data 故障时不阻断取消：cancel 为本地事务+Redis，重复消费安全由幂等注解保障
            log.error("查询消息消费记录异常 messageId : {}", messageId, e);
        }

        if (Objects.nonNull(existMessageConsumerRecordVo) &&
                existMessageConsumerRecordVo.getMessageConsumerStatus().equals(MessageConsumerStatus.CONSUMER_SUCCESS.getCode())) {
            return;
        }
        Long messageConsumerRecordId = null;
        Integer messageConsumerCount = null;
        // 首次消费 → 创建消费记录；重试 → 复用已有记录，消费次数+1
        if (Objects.isNull(existMessageConsumerRecordVo)) {
            InsertMessageConsumerRecordDto insertMessageConsumerRecordDto = new InsertMessageConsumerRecordDto();
            insertMessageConsumerRecordDto.setMessageId(messageId);
            insertMessageConsumerRecordDto.setMessageTraceId(messageTraceId);
            insertMessageConsumerRecordDto.setMessageType(MessageType.DELAY_ORDER_CANCEL.getCode());
            insertMessageConsumerRecordDto.setMessageBusinessesId(programId);
            insertMessageConsumerRecordDto.setMessageTopic(SpringUtil.getPrefixDistinctionName() + "-" + DELAY_ORDER_CANCEL_TOPIC);
            insertMessageConsumerRecordDto.setMessageContent(content);
            try {
                ApiResponse<MessageConsumerRecordVo> insertApiResponse = apiDataClient.insertMessageConsumerRecord(insertMessageConsumerRecordDto);
                if (!insertApiResponse.getCode().equals(BaseCode.SUCCESS.getCode())) {
                    log.error("添加消息消费记录失败 insertMessageConsumerRecordDto : {}", JSON.toJSONString(insertMessageConsumerRecordDto));
                }else {
                    MessageConsumerRecordVo saveMessageConsumerRecordVo = insertApiResponse.getData();
                    messageConsumerRecordId = saveMessageConsumerRecordVo.getId();
                    messageConsumerCount = saveMessageConsumerRecordVo.getMessageConsumerCount();
                }
            }catch (Exception e) {
                // api-data 故障时不阻断取消：消费记录缺失时由消息对账层重投，cancel 幂等保证安全
                log.error("添加消息消费记录异常 insertMessageConsumerRecordDto : {}", JSON.toJSONString(insertMessageConsumerRecordDto), e);
            }
        }else {
            messageConsumerRecordId = existMessageConsumerRecordVo.getId();
            messageConsumerCount = existMessageConsumerRecordVo.getMessageConsumerCount() + 1;
        }
        UpdateMessageConsumerRecordDto updateMessageConsumerRecordDto = null;
        if (Objects.nonNull(messageConsumerRecordId)) {
            updateMessageConsumerRecordDto = new UpdateMessageConsumerRecordDto();
            updateMessageConsumerRecordDto.setId(messageConsumerRecordId);
            updateMessageConsumerRecordDto.setMessageConsumerCount(messageConsumerCount);
            updateMessageConsumerRecordDto.setConsumerTime(DateUtils.now());
        }
        
        try {
            OrderCancelDto orderCancelDto = new OrderCancelDto();
            orderCancelDto.setOrderNumber(orderNumber);
            boolean cancel = orderService.cancel(orderCancelDto);
            if (cancel) {
                log.info("延迟订单取消成功 orderCancelDto : {}",content);
                if (Objects.nonNull(updateMessageConsumerRecordDto)) {
                    updateMessageConsumerRecordDto.setMessageConsumerStatus(MessageConsumerStatus.CONSUMER_SUCCESS.getCode());
                }
            }else {
                log.error("延迟订单取消失败 orderCancelDto : {}",content);
                if (Objects.nonNull(updateMessageConsumerRecordDto)) {
                    updateMessageConsumerRecordDto.setMessageConsumerStatus(MessageConsumerStatus.CONSUMER_FAIL.getCode());
                    updateMessageConsumerRecordDto.setMessageConsumerException("订单取消失败");
                }
            }
        } catch (TicketFlowFrameException e) {
            // TicketFlowFrameException 表示程序层已处理（如订单已支付/已取消），视为消费成功
            if (Objects.nonNull(updateMessageConsumerRecordDto)) {
                updateMessageConsumerRecordDto.setMessageConsumerStatus(MessageConsumerStatus.CONSUMER_SUCCESS.getCode());
            }
        } catch (Exception e) {
            if (Objects.nonNull(updateMessageConsumerRecordDto)) {
                updateMessageConsumerRecordDto.setMessageConsumerStatus(MessageConsumerStatus.CONSUMER_FAIL.getCode());
                updateMessageConsumerRecordDto.setMessageConsumerException(e.getMessage());
            }
        }
        if (Objects.nonNull(updateMessageConsumerRecordDto)) {
            try {
                apiDataClient.updateMessageConsumerRecord(updateMessageConsumerRecordDto);
            }catch (Exception e) {
                log.error("更新消息消费记录失败 id : {}", updateMessageConsumerRecordDto.getId(), e);
            }
        }
    }
    
    @Override
    public String topic() {
        return SpringUtil.getPrefixDistinctionName() + "-" + DELAY_ORDER_CANCEL_TOPIC;
    }
}
