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
 * 订单创建成功后发送延迟取消消息，到期后触发订单自动取消。
 * <p>
 * 核心流程：
 * 生成消息 ID → 写发送日志 → 投递延迟队列 → 更新发送状态。
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

    // 控制是否开启订单延迟取消。默认开启，保障超时未支付订单及库存可自动释放
    @Value("${delay.order.cancel:true}")
    private Boolean delayOrderCancel;

    /**
     * 发送延迟订单取消消息。
     */
    public void sendMessage(DelayOrderCancelDto delayOrderCancelDto) {
        // 关闭延迟取消功能时直接返回。
        if (!delayOrderCancel) {
            return;
        }

        try {
            // 异步发送，避免延迟队列操作占用主请求线程。
            BusinessThreadPool.execute(() -> doSendMessage(delayOrderCancelDto));
        } catch (RejectedExecutionException e) {
            // 线程池满时记录警告，直接快速投递延迟队列（跳过日志同步RPC），或交由 UnpaidOrderCancelTask 兜底，避免主线程雪崩
            log.warn("延迟订单取消消息线程池饱和，跳过同步消息日志 RPC，尝试直接投递延迟队列 orderNumber : {}",
                    delayOrderCancelDto.getOrderNumber(), e);
            try {
                DelayOrderCancelMessageModule delayOrderCancelMessageModule = new DelayOrderCancelMessageModule();
                delayOrderCancelMessageModule.setMessageTraceId(uidGenerator.getUid());
                delayOrderCancelMessageModule.setMessageId(uidGenerator.getUid());
                delayOrderCancelMessageModule.setProgramId(delayOrderCancelDto.getProgramId());
                delayOrderCancelMessageModule.setOrderNumber(delayOrderCancelDto.getOrderNumber());
                delayQueueContext.sendMessage(
                        SpringUtil.getPrefixDistinctionName() + "-" + DELAY_ORDER_CANCEL_TOPIC,
                        JSON.toJSONString(delayOrderCancelMessageModule),
                        DELAY_ORDER_CANCEL_TIME,
                        DELAY_ORDER_CANCEL_TIME_UNIT);
            } catch (Exception ex) {
                log.error("延迟订单取消队列直接投递失败，将由 UnpaidOrderCancelTask 定时任务兜底关单 orderNumber : {}",
                        delayOrderCancelDto.getOrderNumber(), ex);
            }
        }
    }

    /**
     * 延迟订单取消消息发送逻辑。
     * <p>
     * 消息日志记录失败不阻断队列投递，优先保证延迟取消消息能够进入队列。
     */
    void doSendMessage(DelayOrderCancelDto delayOrderCancelDto) {

        // 生成消息追踪 ID 和消息 ID，用于消息生命周期追踪及后续对账。
        Long messageTraceId = uidGenerator.getUid();
        Long messageId = uidGenerator.getUid();

        DelayOrderCancelMessageModule delayOrderCancelMessageModule = new DelayOrderCancelMessageModule();
        delayOrderCancelMessageModule.setMessageTraceId(messageTraceId);
        delayOrderCancelMessageModule.setMessageId(messageId);
        delayOrderCancelMessageModule.setProgramId(delayOrderCancelDto.getProgramId());
        delayOrderCancelMessageModule.setOrderNumber(delayOrderCancelDto.getOrderNumber());

        String messageContent = JSON.toJSONString(delayOrderCancelMessageModule);

        // 第一步：写入消息发送日志。
        // 记录消息 ID、业务 ID、Topic 和消息内容，为后续消息对账提供依据。
        InsertMessageProducerRecordDto insertMessageProducerRecordDto =
                new InsertMessageProducerRecordDto();
        insertMessageProducerRecordDto.setMessageType(MessageType.DELAY_ORDER_CANCEL.getCode());
        insertMessageProducerRecordDto.setMessageTraceId(messageTraceId);
        insertMessageProducerRecordDto.setMessageBusinessesId(
                delayOrderCancelMessageModule.getProgramId());
        insertMessageProducerRecordDto.setMessageId(messageId);
        insertMessageProducerRecordDto.setMessageTopic(
                SpringUtil.getPrefixDistinctionName() + "-" + DELAY_ORDER_CANCEL_TOPIC);
        insertMessageProducerRecordDto.setMessageContent(messageContent);

        MessageProducerRecordVo messageProducerRecordVo = null;

        try {
            ApiResponse<MessageProducerRecordVo> response =
                    apiDataClient.insertMessageProducerRecord(insertMessageProducerRecordDto);

            if (!response.getCode().equals(BaseCode.SUCCESS.getCode())) {
                log.error("添加记录消息发送日志失败，参数 : {}",
                        JSON.toJSONString(insertMessageProducerRecordDto));
            } else {
                messageProducerRecordVo = response.getData();
            }
        } catch (Exception e) {
            // 日志服务异常不影响后续延迟消息投递。
            log.error("添加记录消息发送日志异常，参数 : {}",
                    JSON.toJSONString(insertMessageProducerRecordDto), e);
        }

        // 只有日志成功落库后，才有对应的记录 ID 可以更新发送状态。
        UpdateMessageProducerRecordDto updateMessageProducerRecordDto = null;
        if (Objects.nonNull(messageProducerRecordVo)) {
            updateMessageProducerRecordDto = new UpdateMessageProducerRecordDto();
            updateMessageProducerRecordDto.setId(messageProducerRecordVo.getId());
        }

        // 第二步：投递 Redisson RDelayedQueue。
        // 消息成功进入延迟队列后，订单在指定时间到期执行取消。
        try {
            log.info("延迟订单取消消息进行发送 消息体 : {}", messageContent);

            delayQueueContext.sendMessage(
                    SpringUtil.getPrefixDistinctionName() + "-" + DELAY_ORDER_CANCEL_TOPIC,
                    messageContent,
                    DELAY_ORDER_CANCEL_TIME,
                    DELAY_ORDER_CANCEL_TIME_UNIT);

            if (Objects.nonNull(updateMessageProducerRecordDto)) {
                updateMessageProducerRecordDto.setMessageSendStatus(
                        MessageSendStatus.SEND_SUCCESS.getCode());
            }
        } catch (Exception e) {
            // 队列投递失败时记录失败状态，后续可通过消息对账发现异常。
            log.error("send message error message : {}", messageContent, e);

            if (Objects.nonNull(updateMessageProducerRecordDto)) {
                updateMessageProducerRecordDto.setMessageSendStatus(
                        MessageSendStatus.SEND_FAIL.getCode());
                updateMessageProducerRecordDto.setMessageSendException(e.getMessage());
            }
        }

        // 第三步：更新消息发送结果，形成完整的消息发送记录。
        if (Objects.nonNull(updateMessageProducerRecordDto)) {
            try {
                apiDataClient.updateMessageProducerRecord(updateMessageProducerRecordDto);
            } catch (Exception e) {
                // 状态更新失败不影响已经发送的消息，交由后续对账处理。
                log.error("更新消息发送日志状态失败 id : {}",
                        updateMessageProducerRecordDto.getId(), e);
            }
        }
    }
}