package com.couponseckill.kafka;

/**
 * 发券消息发布抽象：
 * - 生产模式（mock-messaging=false）：Kafka 实现（削峰）
 * - 本地/测试模式（mock-messaging=true）：内存线程池实现，保证无 Kafka 环境全链路可跑
 */
public interface FlashSaleMessagePublisher {

    /**
     * 发布发券请求。
     * @throws RuntimeException 发送失败（调用方负责回补库存）
     */
    void publish(FlashSaleRequestMessage message);
}
