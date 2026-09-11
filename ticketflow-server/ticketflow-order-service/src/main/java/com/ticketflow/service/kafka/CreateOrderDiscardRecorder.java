package com.ticketflow.service.kafka;

import com.alibaba.fastjson.JSON;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.domain.DiscardOrder;
import com.ticketflow.domain.OrderCreateMq;
import com.ticketflow.enums.DiscardOrderReason;
import com.ticketflow.observability.Metrics;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 建单消息"丢弃留痕"的统一入口。
 * <p>
 * 两个调用方：
 * <ul>
 *   <li>消费者自己：消息延迟超过阈值（用户已经不等了）时主动丢弃；</li>
 *   <li>Kafka 容器的错误处理器：可重试异常在退避重试穷尽之后由这里落库留痕。</li>
 * </ul>
 * 留痕之后，资源回滚（座位退回未售、余票恢复）与状态收敛交给对账任务
 * {@code OrderTaskService#discardOrderCompensation}，本类只负责"记这一笔被丢了"。
 */
@Slf4j
@Component
@AllArgsConstructor
public class CreateOrderDiscardRecorder implements ConsumerRecordRecoverer {

    private final RedisCache redisCache;

    private final MeterRegistry meterRegistry;

    /**
     * 写入丢弃记录 + 上报指标。
     * 记录里带完整的建单消息，对账任务据此做幂等回滚。
     */
    public void record(OrderCreateMq orderCreateMq, DiscardOrderReason reason, String errorMsg) {
        redisCache.leftPushForList(
                RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER, orderCreateMq.getProgramId()),
                new DiscardOrder(orderCreateMq, reason.getCode(), errorMsg));
        meterRegistry.counter(Metrics.ORDER_CREATE_FAIL_TOTAL,
                        Metrics.REASON, reasonMetric(reason),
                        Metrics.PROGRAM_ID, String.valueOf(orderCreateMq.getProgramId()))
                .increment();
    }

    /**
     * 错误处理器的恢复回调：退避重试穷尽（或命中不可重试异常）之后执行。
     * <p>
     * 这里不能再往外抛异常——抛出会让这个分区一直卡在这条消息上（既处理不了、offset 也提交不了），
     * 所以留痕失败只记日志、转人工。
     */
    @Override
    public void accept(ConsumerRecord<?, ?> consumerRecord, Exception e) {
        Object recordValue = consumerRecord.value();
        if (Objects.isNull(recordValue)) {
            log.error("建单消息重试穷尽但消息体为空 topic : {} partition : {} offset : {}",
                    consumerRecord.topic(), consumerRecord.partition(), consumerRecord.offset(), e);
            return;
        }
        String value = String.valueOf(recordValue);
        OrderCreateMq orderCreateMq;
        try {
            orderCreateMq = JSON.parseObject(value, OrderCreateMq.class);
        } catch (Exception parseException) {
            // 消息体本身解析不了：没有订单号也没有节目 id，写了丢弃记录也没法补偿，只留日志
            log.error("建单消息重试穷尽且消息体无法解析 topic : {} partition : {} offset : {} value : {}",
                    consumerRecord.topic(), consumerRecord.partition(), consumerRecord.offset(), value, parseException);
            return;
        }
        try {
            record(orderCreateMq, DiscardOrderReason.CREATE_ORDER_FAIL, e.getMessage());
            log.error("建单消息重试穷尽，已写入丢弃记录等待对账回滚 订单号 : {}", orderCreateMq.getOrderNumber(), e);
        } catch (Exception recordException) {
            log.error("写入丢弃记录失败 需人工处理 订单号 : {}", orderCreateMq.getOrderNumber(), recordException);
        }
    }

    private String reasonMetric(DiscardOrderReason reason) {
        return Objects.equals(reason.getCode(), DiscardOrderReason.CONSUMER_DELAY.getCode())
                ? Metrics.REASON_CREATE_ORDER_DELAY : Metrics.REASON_CREATE_ORDER_FAIL;
    }
}
