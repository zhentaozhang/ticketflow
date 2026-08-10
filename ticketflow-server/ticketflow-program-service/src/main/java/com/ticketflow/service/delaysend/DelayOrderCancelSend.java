package com.ticketflow.service.delaysend;

import com.alibaba.fastjson2.JSON;
import com.baidu.fsg.uid.UidGenerator;
import com.ticketflow.BusinessThreadPool;
import com.ticketflow.client.ApiDataClient;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.context.DelayQueueContext;
import com.ticketflow.core.SpringUtil;
import com.ticketflow.dto.DelayOrderCancelDto;
import com.ticketflow.dto.InsertMessageProducerRecordDto;
import com.ticketflow.dto.UpdateMessageProducerRecordDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.MessageSendStatus;
import com.ticketflow.enums.MessageType;
import com.ticketflow.module.DelayOrderCancelMessageModule;
import com.ticketflow.vo.MessageProducerRecordVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

import static com.ticketflow.constant.ProgramOrderConstant.DELAY_ORDER_CANCEL_TIME;
import static com.ticketflow.constant.ProgramOrderConstant.DELAY_ORDER_CANCEL_TIME_UNIT;
import static com.ticketflow.constant.ProgramOrderConstant.DELAY_ORDER_CANCEL_TOPIC;

/**
 * 延迟订单取消消息发送器。
 * 订单创建成功后（V1/V2/V3 同步路径），发送一条延迟队列消息，
 * 在 DELAY_ORDER_CANCEL_TIME 后触发订单取消。
 *
 * 发送流程：生成唯一 messageId → 插入消息发送日志 → push 到延迟队列 →
 *         更新发送状态（成功/失败）
 * 开关：delay.order.cancel=false 时可关闭（如 V4 异步场景）
 *
 * 通过 DelayQueueContext.sendMessage() 发送到 Redisson RDelayedQueue
 */
@Slf4j
@Component
public class DelayOrderCancelSend {
    
    @Autowired
    private UidGenerator uidGenerator;
    
    @Autowired
    private DelayQueueContext delayQueueContext;
    
    
    @Autowired
    private ApiDataClient apiDataClient;
    
    @Value("${delay.order.cancel:false}")
    private Boolean delayOrderCancel;
    
    /**
     * 发送延迟订单取消消息。
     * 订单创建成功后调用，在 DELAY_ORDER_CANCEL_TIME 后触发订单自动取消。
     * 流程：生成消息追踪ID → 记录消息发送日志 → push 到 Redisson RDelayedQueue → 更新发送状态
     * <p>
     * 开关 {@code delay.order.cancel=false} 可关闭该路径（V4 异步场景由上游自行处理取消）。
     *
     * @param delayOrderCancelDto 订单取消参数（programId + orderNumber）
     */
    public void sendMessage(DelayOrderCancelDto delayOrderCancelDto){
        if (!delayOrderCancel){
            return;
        }
        try {
            BusinessThreadPool.execute(() -> doSendMessage(delayOrderCancelDto));
        } catch (RejectedExecutionException e) {
            // 线程池饱和时降级同步发送：延迟取消消息丢失 = 订单永不被自动取消，不能吞掉
            log.error("延迟订单取消消息线程池饱和，降级同步发送 orderNumber : {}", delayOrderCancelDto.getOrderNumber(), e);
            doSendMessage(delayOrderCancelDto);
        }
    }

    /**
     * 延迟订单取消消息发送逻辑（异步线程或线程池饱和降级时同步执行）。
     * 消息日志失败不阻断延迟消息发送：RDelayedQueue 在 2 分钟后到期消费，
     * 届时 api-data 大概率已恢复，取消逻辑照常执行
     */
    void doSendMessage(DelayOrderCancelDto delayOrderCancelDto){
        Long messageTraceId = uidGenerator.getUid();
        Long messageId = uidGenerator.getUid();

        DelayOrderCancelMessageModule delayOrderCancelMessageModule = new DelayOrderCancelMessageModule();
        delayOrderCancelMessageModule.setMessageTraceId(messageTraceId);
        delayOrderCancelMessageModule.setMessageId(messageId);
        delayOrderCancelMessageModule.setProgramId(delayOrderCancelDto.getProgramId());
        delayOrderCancelMessageModule.setOrderNumber(delayOrderCancelDto.getOrderNumber());

        String messageContent = JSON.toJSONString(delayOrderCancelMessageModule);
        // 第一步：插入消息发送日志（记录消息追踪信息，后续对账使用）
        InsertMessageProducerRecordDto insertMessageProducerRecordDto = new InsertMessageProducerRecordDto();
        insertMessageProducerRecordDto.setMessageType(MessageType.DELAY_ORDER_CANCEL.getCode());
        insertMessageProducerRecordDto.setMessageTraceId(messageTraceId);
        insertMessageProducerRecordDto.setMessageBusinessesId(delayOrderCancelMessageModule.getProgramId());
        insertMessageProducerRecordDto.setMessageId(messageId);
        insertMessageProducerRecordDto.setMessageTopic(SpringUtil.getPrefixDistinctionName() + "-" + DELAY_ORDER_CANCEL_TOPIC);
        insertMessageProducerRecordDto.setMessageContent(messageContent);
        MessageProducerRecordVo messageProducerRecordVo = null;
        try {
            ApiResponse<MessageProducerRecordVo> insertMessageProducerRecordApiResponse = apiDataClient.insertMessageProducerRecord(insertMessageProducerRecordDto);
            if (!insertMessageProducerRecordApiResponse.getCode().equals(BaseCode.SUCCESS.getCode())){
                log.error("添加记录消息发送日志失败，参数 : {}", JSON.toJSONString(insertMessageProducerRecordDto));
            }else {
                messageProducerRecordVo = insertMessageProducerRecordApiResponse.getData();
            }
        }catch (Exception e) {
            log.error("添加记录消息发送日志异常，参数 : {}", JSON.toJSONString(insertMessageProducerRecordDto), e);
        }

        UpdateMessageProducerRecordDto updateMessageProducerRecordDto = null;
        if (Objects.nonNull(messageProducerRecordVo)) {
            updateMessageProducerRecordDto = new UpdateMessageProducerRecordDto();
            updateMessageProducerRecordDto.setId(messageProducerRecordVo.getId());
        }
        // 第二步：发送到延迟队列（Redisson RDelayedQueue），成功后更新发送状态，失败则记录异常
        try {
            log.info("延迟订单取消消息进行发送 消息体 : {}",messageContent);
            delayQueueContext.sendMessage(SpringUtil.getPrefixDistinctionName() + "-" + DELAY_ORDER_CANCEL_TOPIC,
                    messageContent, DELAY_ORDER_CANCEL_TIME, DELAY_ORDER_CANCEL_TIME_UNIT);
            if (Objects.nonNull(updateMessageProducerRecordDto)) {
                updateMessageProducerRecordDto.setMessageSendStatus(MessageSendStatus.SEND_SUCCESS.getCode());
            }
        }catch (Exception e) {
            log.error("send message error message : {}",messageContent,e);
            if (Objects.nonNull(updateMessageProducerRecordDto)) {
                updateMessageProducerRecordDto.setMessageSendStatus(MessageSendStatus.SEND_FAIL.getCode());
                updateMessageProducerRecordDto.setMessageSendException(e.getMessage());
            }
        }
        // 第三步：更新消息发送日志状态
        if (Objects.nonNull(updateMessageProducerRecordDto)) {
            try {
                apiDataClient.updateMessageProducerRecord(updateMessageProducerRecordDto);
            }catch (Exception e) {
                log.error("更新消息发送日志状态失败 id : {}", updateMessageProducerRecordDto.getId(), e);
            }
        }
    }
}
