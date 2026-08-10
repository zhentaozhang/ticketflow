package com.ticketflow.service.kafka;

import com.alibaba.fastjson.JSON;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.domain.DiscardOrder;
import com.ticketflow.domain.OrderCreateMq;
import com.ticketflow.dto.OrderTicketUserCreateDto;
import com.ticketflow.enums.DiscardOrderReason;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.service.OrderService;
import com.ticketflow.util.StringUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.ticketflow.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;

/**
 * Kafka 异步订单创建消费者。
 * 接收 V4/V41 策略发送的创建订单消息，解析 OrderCreateMq，
 * 调用 OrderService.createMq() 完成订单持久化。
 *
 * 幂等保障：createMq 的 @RepeatExecuteLimit（60秒）防止同一订单号重复建单。
 * 延迟消费：收到消息后等待 MESSAGE_DELAY_TIME（60秒），
 *           给 program-service 留出 Redis 数据同步时间。
 *
 * 丢弃订单：超过丢弃时间阈值的消息写入 DISCARD_ORDER 并记录原因
 */
@Slf4j
@AllArgsConstructor
@Component
public class CreateOrderConsumer {
    
    @Autowired
    private OrderService orderService;
    
    @Autowired
    private RedisCache redisCache;
    
    @Autowired
    private MeterRegistry meterRegistry;
    
    public static Long MESSAGE_DELAY_TIME = 60000L;
    
    /**
     * 批量消费（batch listener）：一次 poll 处理最多 200 条，减少 poll/调度开销。
     * 未超时消息聚合后调 OrderService.createMqBatch（批量 Feign 扣减 + 逐单建单），
     * 提升消费端吞吐。并行度与 topic 分区数一致（create_order topic 12 分区）。
     */
    @KafkaListener(containerFactory = "batchKafkaListenerContainerFactory",
            topics = {SPRING_INJECT_PREFIX_DISTINCTION_NAME+"-"+"${spring.kafka.topic:create_order}"}, concurrency = "12")
    public void consumerOrderMessage(List<ConsumerRecord<String,String>> consumerRecords){
        List<OrderCreateMq> validMqList = new ArrayList<>();
        for (ConsumerRecord<String,String> consumerRecord : consumerRecords) {
            String value = consumerRecord.value();
            if (StringUtil.isEmpty(value)) {
                continue;
            }
            OrderCreateMq orderCreateMq = JSON.parseObject(value, OrderCreateMq.class);
            if (isOverDelay(orderCreateMq)) {
                discardByDelay(orderCreateMq);
            } else {
                validMqList.add(orderCreateMq);
            }
        }
        if (!validMqList.isEmpty()) {
            try {
                List<OrderCreateMq> failedMqList = orderService.createMqBatch(validMqList);
                for (OrderCreateMq orderCreateMq : failedMqList) {
                    discardByCreateFail(orderCreateMq);
                }
            } catch (Exception e) {
                log.error("批量创建订单失败 error", e);
                for (OrderCreateMq orderCreateMq : validMqList) {
                    discardByCreateFail(orderCreateMq);
                }
            }
        }
    }

    private boolean isOverDelay(OrderCreateMq orderCreateMq){
        long createOrderTimeTimestamp = orderCreateMq.getCreateOrderTime().getTime();
        return System.currentTimeMillis() - createOrderTimeTimestamp > MESSAGE_DELAY_TIME;
    }

    /**
     * 消费延迟超时丢弃：回滚 Redis 锁定座位 + 写入 DISCARD_ORDER + Prometheus 计数。
     */
    private void discardByDelay(OrderCreateMq orderCreateMq){
        try {
            long delayTime = System.currentTimeMillis() - orderCreateMq.getCreateOrderTime().getTime();
            Map<Long, List<OrderTicketUserCreateDto>> orderTicketUserSeatList =
                    orderCreateMq.getOrderTicketUserCreateDtoList().stream().collect(Collectors.groupingBy(OrderTicketUserCreateDto::getTicketCategoryId));
            //key: 节目票档id value: 座位id集合
            Map<Long,List<Long>> seatMap = new HashMap<>(orderTicketUserSeatList.size());
            orderTicketUserSeatList.forEach((k,v) -> {
                seatMap.put(k,v.stream().map(OrderTicketUserCreateDto::getSeatId).collect(Collectors.toList()));
            });
            log.info("消费到kafka的创建订单消息延迟时间大于了 {} 毫秒 此订单消息被丢弃 订单号 : {} 座位信息 : {}",
                    delayTime,orderCreateMq.getOrderNumber(),JSON.toJSONString(seatMap));
            //释放该订单在 Redis 中锁定的座位，避免座位永久锁死
            try {
                orderService.rollbackProgramSeatByDiscard(orderCreateMq);
            }catch (Exception rollbackException) {
                log.error("丢弃订单回滚Redis座位失败 订单号 : {}",orderCreateMq.getOrderNumber(),rollbackException);
            }
            pushDiscardOrder(orderCreateMq, DiscardOrderReason.CONSUMER_DELAY.getCode(), "消费延迟");
            //上报指标给Promethus
            meterRegistry.counter("ticketflow_order_create_fail_total", "reason", "CREATE_ORDER_DELAY", "programId", String.valueOf(orderCreateMq.getProgramId())).increment();
        } catch (Exception e) {
            log.error("消费延迟丢弃处理失败 订单号 : {}", orderCreateMq.getOrderNumber(), e);
        }
    }

    /**
     * 建单失败丢弃：写入 DISCARD_ORDER + Prometheus 计数（等待对账任务补偿）。
     */
    private void discardByCreateFail(OrderCreateMq orderCreateMq){
        try {
            pushDiscardOrder(orderCreateMq, DiscardOrderReason.CREATE_ORDER_FAIL.getCode(), "建单失败");
            meterRegistry.counter("ticketflow_order_create_fail_total", "reason", "CREATE_ORDER_FAIL", "programId", String.valueOf(orderCreateMq.getProgramId())).increment();
            log.error("创建订单失败已入丢弃队列 订单号 : {}", orderCreateMq.getOrderNumber());
        } catch (Exception e) {
            log.error("建单失败入丢弃队列异常 订单号 : {}", orderCreateMq.getOrderNumber(), e);
        }
    }

    private void pushDiscardOrder(OrderCreateMq orderCreateMq, Integer reason, String reasonDesc){
        redisCache.leftPushForList(RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER,
                orderCreateMq.getProgramId()), new DiscardOrder(orderCreateMq, reason, reasonDesc));
    }
}
