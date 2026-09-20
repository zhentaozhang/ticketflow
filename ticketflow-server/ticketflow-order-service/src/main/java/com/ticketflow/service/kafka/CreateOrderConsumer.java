package com.ticketflow.service.kafka;

import com.alibaba.fastjson.JSON;
import com.ticketflow.domain.OrderCreateMq;
import com.ticketflow.dto.OrderTicketUserCreateDto;
import com.ticketflow.enums.DiscardOrderReason;
import com.ticketflow.enums.ProgramOrderVersion;
import com.ticketflow.observability.BusinessMetrics;
import com.ticketflow.observability.Metrics;
import com.ticketflow.service.OrderService;
import com.ticketflow.util.StringUtil;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.ticketflow.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;

/**
 * Kafka 异步订单创建消费者。
 * 接收 V4/V5 策略发送的创建订单消息，解析 OrderCreateMq，
 * 调用 OrderService.createMq() 完成订单持久化。
 * <p>
 * 幂等保障：createMq 对同 orderNumber 重复消息幂等成功（selectOne 防重 + DB 唯一索引兜底 +
 * ORDER_EXIST 特判不写 DISCARD_ORDER）；program-service 侧对"座位锁定 + 余票扣减"另有一层
 * 幂等守卫（@RepeatExecuteLimit + "必须是未售"校验），所以重复投递不会二次扣减。
 * <p>
 * 可靠性（三层）：<b>至少一次</b>——offset 关闭自动提交，异常上抛给
 * {@code OrderKafkaErrorHandlerConfig} 的 DefaultErrorHandler，瞬时故障退避重试；
 * <b>幂等</b>——重复投递由上面的幂等机制吸收；
 * <b>对账</b>——重试穷尽后由 {@link CreateOrderDiscardRecorder} 写 DISCARD_ORDER，交对账回滚资源。
 * <p>
 * 丢弃订单：消息延迟超过 MESSAGE_DELAY_TIME（用户已经不等了）时主动丢弃并留痕。
 */
@Slf4j
@AllArgsConstructor
@Component
public class CreateOrderConsumer {

    @Autowired
    private OrderService orderService;

    @Autowired
    private CreateOrderDiscardRecorder discardRecorder;

    @Autowired
    private MeterRegistry meterRegistry;

    /**
     * 丢弃闸：消息从下单到被处理超过这个时长就直接丢弃（那时用户已经不等了）。
     * <p>
     * 包级可见（而不是 public）的目的：让同包的测试能把“它和重试总时长必须是咬合的”这件事断言下来
     * （见 {@code CreateOrderConsumerTest#重试上限必须小于消费端丢弃闸}）。
     * 以前它是 {@code public static Long}——既能被外部改、又不是常量语义。
     */
    static final long MESSAGE_DELAY_TIME = 60000L;

    /**
     * 消费并行度与 topic 分区数一致（create_order topic 48 分区）。
     * 每分区一个 consumer，提升订单创建消费吞吐，缓解高到达率下消费积压/超时丢弃。
     * 注意：分区数变更需同步执行 kafka-topics.sh --alter --partitions 48（见部署文档）。
     */
    @KafkaListener(topics = {SPRING_INJECT_PREFIX_DISTINCTION_NAME + "-" + "${spring.kafka.topic:create_order}"}, concurrency = "48")
    public void consumerOrderMessage(ConsumerRecord<String, String> consumerRecord) {
        String value = consumerRecord.value();
        if (StringUtil.isEmpty(value)) {
            return;
        }
        OrderCreateMq orderCreateMq = JSON.parseObject(value, OrderCreateMq.class);

        long createOrderTimeTimestamp = orderCreateMq.getCreateOrderTime().getTime();
        long currentTimeTimestamp = System.currentTimeMillis();
        long delayTime = currentTimeTimestamp - createOrderTimeTimestamp;

        // 热路径只打关键字段：完整消息体是高并发下的日志放大源，需要时开 debug
        log.info("消费到kafka的创建订单消息 订单号 : {} 延迟时间 : {} 毫秒", orderCreateMq.getOrderNumber(), delayTime);
        if (log.isDebugEnabled()) {
            log.debug("消费到kafka的创建订单消息 消息体 : {}", value);
        }

        // 消费延迟是“离丢弃闸（MESSAGE_DELAY_TIME）还有多远”的直接度量，
        // 比“积压条数”更贴近业务，所以每个版本都记一笔
        String versionTag = Objects.toString(orderCreateMq.getOrderVersion(), Metrics.RESULT_UNKNOWN);
        BusinessMetrics.recordSeconds(meterRegistry, Metrics.ORDER_CREATE_DELAY_SECONDS,
                delayTime / 1000.0d, Metrics.VERSION, versionTag);

        // 超过 MESSAGE_DELAY_TIME(60s) 的消息视为超时 → 丢入 DISCARD_ORDER（Redis list）用于后续对账分析 + Prometheus 计数
        // V5：Redis 为唯一库存权威，积压消息不丢弃，继续建单（最终一致由对账兜底），避免"Redis 已扣但订单丢失"
        boolean isV5 = Objects.equals(orderCreateMq.getOrderVersion(), ProgramOrderVersion.V5_VERSION.getValue());
        if (!isV5 && delayTime > MESSAGE_DELAY_TIME) {
            Map<Long, List<OrderTicketUserCreateDto>> orderTicketUserSeatList =
                    orderCreateMq.getOrderTicketUserCreateDtoList().stream().collect(Collectors.groupingBy(OrderTicketUserCreateDto::getTicketCategoryId));
            //key: 节目票档id value: 座位id集合
            Map<Long, List<Long>> seatMap = new HashMap<>(orderTicketUserSeatList.size());
            orderTicketUserSeatList.forEach((k, v) -> {
                seatMap.put(k, v.stream().map(OrderTicketUserCreateDto::getSeatId).collect(Collectors.toList()));
            });
            log.info("消费到kafka的创建订单消息延迟时间大于了 {} 毫秒 此订单消息被丢弃 订单号 : {} 座位信息 : {}",
                    delayTime, orderCreateMq.getOrderNumber(), JSON.toJSONString(seatMap));
            //释放该订单在 Redis 中锁定的座位，避免座位永久锁死
            try {
                orderService.rollbackProgramSeatByDiscard(orderCreateMq);
            } catch (Exception rollbackException) {
                log.error("丢弃订单回滚Redis座位失败 订单号 : {}", orderCreateMq.getOrderNumber(), rollbackException);
            }
            // 丢弃留痕（含 Prometheus 计数）：这是"用户已不等、我们主动放弃"这条路径，
            // 与"处理失败"共用同一个留痕入口，靠 reason 区分
            discardRecorder.record(orderCreateMq, DiscardOrderReason.CONSUMER_DELAY, "消费延迟");
            return;
        }

        // 建单失败不再在这里吞掉：异常上抛给容器的错误处理器，由它决定“退避重试”还是“重试穷尽后留痕”。
        // 在这里 catch 住会让 offset 照常提交，等于任何一次瞬时故障都直接变成“用户丢单”。
        String orderNumber = orderService.createMq(orderCreateMq);
        // 真的落库了才计数：这是端到端落库率的分子（受理成功数在 program-service 侧）
        BusinessMetrics.increment(meterRegistry, Metrics.ORDER_CREATED_TOTAL, Metrics.VERSION, versionTag);
        log.info("消费到kafka的创建订单消息 创建订单成功 订单号 : {}", orderNumber);
    }
}
