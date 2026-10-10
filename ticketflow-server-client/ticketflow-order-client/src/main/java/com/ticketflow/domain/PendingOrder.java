package com.ticketflow.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发送超时待确认订单。
 * 请求侧 Kafka 发送确认超时降级为"已受理"时写入 Redis PENDING 队列，
 * 由 order-service 对账任务扫描：订单已建则移除，未建则回滚 Redis 座位。
 */
@Data
@NoArgsConstructor
public class PendingOrder {
    /**
     * 参数信息
     * */
    private OrderCreateMq orderCreateMq;

    public PendingOrder(OrderCreateMq orderCreateMq) {
        this.orderCreateMq = orderCreateMq;
    }
}
