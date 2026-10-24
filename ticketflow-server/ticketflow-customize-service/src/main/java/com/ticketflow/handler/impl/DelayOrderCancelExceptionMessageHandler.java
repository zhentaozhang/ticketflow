package com.ticketflow.handler.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ticketflow.context.DelayQueueContext;
import com.ticketflow.entity.MessageProducerRecord;
import com.ticketflow.enums.MessageSendStatus;
import com.ticketflow.enums.MessageType;
import com.ticketflow.enums.ReconciliationStatus;
import com.ticketflow.handler.ExceptionMessageHandler;
import com.ticketflow.mapper.MessageProducerRecordMapper;
import com.ticketflow.observability.BusinessMetrics;
import com.ticketflow.observability.Metrics;
import com.ticketflow.util.DateUtils;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.ticketflow.constant.ProgramOrderConstant.DELAY_ORDER_CANCEL_TIME;
import static com.ticketflow.constant.ProgramOrderConstant.DELAY_ORDER_CANCEL_TIME_UNIT;

/**
 * 延迟订单取消异常处理器——处理订单取消失败的补偿逻辑。
 *
 * 调用 order-service 的延迟取消接口，
 * 在首次取消失败时记录异常信息供后续重试
 *
 * 重投上限：旧实现对账每轮都把 {@code reconciliation_status} 重置回「未对账」，
 * 既不累计次数也不写终态，一条永远消费不了的消息会每分钟被无限重投，
 * 既刷爆延迟队列也永远没有人工出口。现在每次重投 +1（{@code retry_count}），
 * 用指数退避写 {@code next_retry_time} 控制节奏，超过上限转
 * {@link ReconciliationStatus#RECONCILIATION_FAIL} 并打 {@link Metrics#MESSAGE_RECONCILE_TOTAL} 指标。
 */
@Slf4j
@Component
public class DelayOrderCancelExceptionMessageHandler implements ExceptionMessageHandler {

    /** 同一条消息最多被自动重投的次数，用尽即转人工 */
    private static final int MAX_RETRY_ATTEMPTS = 5;

    /** 单轮最多捞取的消息数：旧实现无 limit，消息堆积时会一次把整表读进内存 */
    private static final int RECONCILE_BATCH_SIZE = 200;

    /** 退避基数：第 n 次重投等待 60 * 2^(n-1) 秒 */
    private static final long BASE_BACKOFF_SECONDS = 60L;

    /** 退避上限，避免算出天级的等待 */
    private static final long MAX_BACKOFF_SECONDS = 3600L;

    @Autowired
    private MessageProducerRecordMapper messageProducerRecordMapper;

    @Autowired
    private DelayQueueContext delayQueueContext;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @Override
    public List<MessageProducerRecord> noReconciliationMessageProducerRecordList() {
        // 计算"合理的发送时间边界"：当前时间 - 延迟时间（即该消息理论上应被消费的时间点）
        Date date;
        switch (DELAY_ORDER_CANCEL_TIME_UNIT) {
            case MINUTES -> date = DateUtils.addMinute(DateUtils.now(),-DELAY_ORDER_CANCEL_TIME.intValue());
            case HOURS -> date = DateUtils.addHour(DateUtils.now(),-DELAY_ORDER_CANCEL_TIME.intValue());
            case DAYS -> date = DateUtils.addDay(DateUtils.now(),-DELAY_ORDER_CANCEL_TIME.intValue());
            default -> date = DateUtils.addSecond(DateUtils.now(),-DELAY_ORDER_CANCEL_TIME.intValue());
        }
        Date now = DateUtils.now();
        // 查询未对账且发送时间 < 理论消费时间 - 10s 的消息（超过等待期仍未对账的异常消息）
        // 退避：只捞取"没排下次时间"或"下次时间已到"的消息，否则每轮都会把刚排队的消息再捞一遍
        Wrapper<MessageProducerRecord> messageRecordWrapper = Wrappers.lambdaQuery(MessageProducerRecord.class)
                .eq(MessageProducerRecord::getReconciliationStatus, ReconciliationStatus.RECONCILIATION_NO.getCode())
                .lt(MessageProducerRecord::getSendTime, DateUtils.addSecond(date, -10))
                .and(wrapper -> wrapper.isNull(MessageProducerRecord::getNextRetryTime)
                        .or().le(MessageProducerRecord::getNextRetryTime, now))
                .orderByAsc(MessageProducerRecord::getSendTime)
                .last("limit " + RECONCILE_BATCH_SIZE);
        return messageProducerRecordMapper.selectList(messageRecordWrapper);
    }
    
    @Override
    public Boolean handle(MessageProducerRecord messageProducerRecord) {
        int doneAttempts = messageProducerRecord.getRetryCount() == null ? 0 : messageProducerRecord.getRetryCount();
        int attempts = doneAttempts + 1;
        String messageContent = messageProducerRecord.getMessageContent();
        MessageProducerRecord udpateMessageProducerRecord = new MessageProducerRecord();
        udpateMessageProducerRecord.setId(messageProducerRecord.getId());
        udpateMessageProducerRecord.setReconciliationStatus(ReconciliationStatus.RECONCILIATION_NO.getCode());
        udpateMessageProducerRecord.setRetryCount(attempts);
        if (attempts > MAX_RETRY_ATTEMPTS) {
            // 终态：不再自动重投，留人工出口（管理端可按 messageId 手工重投，见 MessageRecordService#executeExceptionMessage）
            udpateMessageProducerRecord.setReconciliationStatus(ReconciliationStatus.RECONCILIATION_FAIL.getCode());
            udpateMessageProducerRecord.setMessageSendException(truncate("自动重投 " + MAX_RETRY_ATTEMPTS + " 次仍未成功，已转人工处理"));
            udpateMessageProducerRecord.setNextRetryTime(null);
            messageProducerRecordMapper.updateById(udpateMessageProducerRecord);
            log.error("[MESSAGE_RECONCILE_DEAD] 延迟订单取消消息自动重投达到上限，转人工处理 messageId : {} 已重投 : {} messageContent : {}",
                    messageProducerRecord.getMessageId(), doneAttempts, messageContent);
            BusinessMetrics.increment(meterRegistry, Metrics.MESSAGE_RECONCILE_TOTAL,
                    Metrics.RESULT, Metrics.RESULT_DEAD);
            return true;
        }
        boolean sendSuccess;
        try {
            log.info("延迟订单取消消息进行发送 消息体 : {}",messageContent);
            // 异常补偿：延迟改为 1 秒（立即消费），而非原始延迟时间
            delayQueueContext.sendMessage(messageProducerRecord.getMessageTopic(), messageContent, 1, TimeUnit.SECONDS);
            udpateMessageProducerRecord.setMessageSendStatus(MessageSendStatus.SEND_SUCCESS.getCode());
            sendSuccess = true;
        }catch (Exception e) {
            log.error("send message error message : {}",messageContent,e);
            udpateMessageProducerRecord.setMessageSendStatus(MessageSendStatus.SEND_FAIL.getCode());
            udpateMessageProducerRecord.setMessageSendException(truncate(e.getMessage()));
            sendSuccess = false;
        }
        // 无论本次投递成功与否都排下一次：投递成功但消费又失败的，下一轮继续重投
        udpateMessageProducerRecord.setNextRetryTime(
                DateUtils.addSecond(DateUtils.now(), (int) backoffSeconds(attempts)));
        messageProducerRecordMapper.updateById(udpateMessageProducerRecord);
        BusinessMetrics.increment(meterRegistry, Metrics.MESSAGE_RECONCILE_TOTAL,
                Metrics.RESULT, sendSuccess ? Metrics.RESULT_SUCCESS : Metrics.RESULT_RETRY);
        return true;
    }

    /**
     * 指数退避：第 n 次重投等待 BASE * 2^(n-1) 秒，封顶 {@link #MAX_BACKOFF_SECONDS}。
     */
    private long backoffSeconds(int attempts) {
        int shift = Math.max(0, Math.min(attempts - 1, 10));
        return Math.min(BASE_BACKOFF_SECONDS << shift, MAX_BACKOFF_SECONDS);
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 250 ? message : message.substring(0, 250);
    }
    
    @Override
    public MessageType getMessageType() {
        return MessageType.DELAY_ORDER_CANCEL;
    }
}
