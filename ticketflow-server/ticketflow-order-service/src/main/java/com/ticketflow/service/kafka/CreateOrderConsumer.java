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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.ticketflow.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;

/**
 * Kafka 异步订单创建消费者。
 * 接收 V4/V5 策略发送的创建订单消息，解析 OrderCreateMq，
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
     * 消费并行度与 topic 分区数一致（create_order topic 12 分区）。
     * 每分区一个 consumer，提升订单创建消费吞吐，缓解高到达率下消费积压/超时丢弃。
     */
    @KafkaListener(topics = {SPRING_INJECT_PREFIX_DISTINCTION_NAME+"-"+"${spring.kafka.topic:create_order}"}, concurrency = "12")
    public void consumerOrderMessage(ConsumerRecord<String,String> consumerRecord){
        String value = consumerRecord.value();
        if (StringUtil.isEmpty(value)) {
            return;
        }
        OrderCreateMq orderCreateMq = JSON.parseObject(value, OrderCreateMq.class);
        try {
            long createOrderTimeTimestamp = orderCreateMq.getCreateOrderTime().getTime();
            
            long currentTimeTimestamp = System.currentTimeMillis();
            
            long delayTime = currentTimeTimestamp - createOrderTimeTimestamp;
            
            log.info("消费到kafka的创建订单消息 消息体: {} 延迟时间 : {} 毫秒",value,delayTime);
            
            // 超过 MESSAGE_DELAY_TIME(60s) 的消息视为超时 → 丢入 DISCARD_ORDER（Redis list）用于后续对账分析 + Prometheus 计数
            if (currentTimeTimestamp - createOrderTimeTimestamp > MESSAGE_DELAY_TIME) {
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
                //将延迟丢弃的订单放入redis中
                redisCache.leftPushForList(RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER,
                        orderCreateMq.getProgramId()),new DiscardOrder(orderCreateMq, DiscardOrderReason.CONSUMER_DELAY.getCode(), "消费延迟"));
                //上报指标给Promethus
                meterRegistry.counter("ticketflow_order_create_fail_total", "reason", "CREATE_ORDER_DELAY", "programId", String.valueOf(orderCreateMq.getProgramId())).increment();
            }else {
                String orderNumber = orderService.createMq(orderCreateMq);
                log.info("消费到kafka的创建订单消息 创建订单成功 订单号 : {}",orderNumber);
            }
        }catch (Exception e) {
            //将创建失败的订单放入redis中（等待对账任务补偿），同时上报 Prometheus 指标
            redisCache.leftPushForList(RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER,
                    orderCreateMq.getProgramId()),new DiscardOrder(orderCreateMq, DiscardOrderReason.CREATE_ORDER_FAIL.getCode(), e.getMessage()));
            //上报指标给Promethus
            meterRegistry.counter("ticketflow_order_create_fail_total", "reason", "CREATE_ORDER_FAIL", "programId", String.valueOf(orderCreateMq.getProgramId())).increment();
            log.error("处理消费到kafka的创建订单消息失败 error",e);
        }
    }
}
