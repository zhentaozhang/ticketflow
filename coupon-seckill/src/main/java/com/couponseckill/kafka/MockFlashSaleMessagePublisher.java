package com.couponseckill.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 内存发券消息实现（mock-messaging=true）：
 * 模拟 Kafka 的异步投递与削峰，本地/测试环境无 Kafka 也能跑通"抢购→异步发券→结果回写"全链路。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "coupon-seckill.mock-messaging", havingValue = "true", matchIfMissing = true)
public class MockFlashSaleMessagePublisher implements FlashSaleMessagePublisher {

    private final ObjectMapper objectMapper;
    private final FlashSaleIssueHandler handler;

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            8, 32, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(100_000),
            new ThreadPoolExecutor.CallerRunsPolicy());

    public MockFlashSaleMessagePublisher(ObjectMapper objectMapper, FlashSaleIssueHandler handler) {
        this.objectMapper = objectMapper;
        this.handler = handler;
    }

    @Override
    public void publish(FlashSaleRequestMessage message) {
        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            throw new IllegalStateException("serialize message failed", e);
        }
        executor.execute(() -> handleWithRetry(json, 0));
    }

    private void handleWithRetry(String json, int attempt) {
        try {
            handler.handle(json);
        } catch (Exception e) {
            if (attempt < 5) {
                log.warn("[mock-msg] retry attempt={} msg={}", attempt + 1, json, e);
                try {
                    Thread.sleep(100L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                handleWithRetry(json, attempt + 1);
            } else {
                log.error("[mock-msg] give up after 5 attempts: {}", json, e);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
