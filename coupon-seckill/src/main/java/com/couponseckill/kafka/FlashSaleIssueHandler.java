package com.couponseckill.kafka;

import com.couponseckill.service.CouponIssueService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 发券消息统一消费逻辑：
 * - mock 模式：由 MockFlashSaleMessagePublisher 直接调用
 * - 生产模式：由 KafkaFlashSaleRequestConsumer（@KafkaListener）调用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlashSaleIssueHandler {

    private final ObjectMapper objectMapper;
    private final CouponIssueService couponIssueService;

    /**
     * 处理一条发券消息。异常向上抛出，由调用方决定重试策略。
     */
    public void handle(String json) {
        FlashSaleRequestMessage message;
        try {
            message = objectMapper.readValue(json, FlashSaleRequestMessage.class);
        } catch (Exception e) {
            // 消息体损坏：无法重试，记录后丢弃（避免死循环）
            log.error("[issue-msg-invalid] dropped: {}", json, e);
            return;
        }
        couponIssueService.issue(message);
    }
}
