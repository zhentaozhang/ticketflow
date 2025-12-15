package com.ticketflow.service.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 每秒请求数限流计数器（JVM 本地）。
 * AtomicInteger + 时间窗口重置，超阈值返回 true，
 * 被 UserCaptchaService 用于决定是否需要弹验证码
 */
@Slf4j
@Component
public class RequestCounter {

    // 当前时间窗口内已统计的请求数
    private final AtomicInteger count = new AtomicInteger(0);

    // 上一次重置计数器的时间戳（毫秒）
    private final AtomicLong lastResetTime = new AtomicLong(System.currentTimeMillis());

    // 从配置文件读取：每秒最大请求数，默认 1000
    @Value("${request_count_threshold:1000}")
    private int maxRequestsPerSecond = 1000;

    // 判断本次请求是否超限（true=超限）
    public synchronized boolean onRequest() {
        long currentTime = System.currentTimeMillis();
        long differenceValue = 1000; // 时间窗口长度：1 秒

        // ① 如果距离上次重置已满 1 秒，重置计数器和重置时间
        if (currentTime - lastResetTime.get() >= differenceValue) {
            count.set(0);
            lastResetTime.set(currentTime);
        }

        // ② 计数器加 1，并判断是否超过阈值
        if (count.incrementAndGet() > maxRequestsPerSecond) {
            log.warn("请求超过每秒{}次限制", maxRequestsPerSecond);
            // ③ 超限后立即重置（让后续请求重新计时，避免一直阻塞）
            count.set(0);
            lastResetTime.set(System.currentTimeMillis());
            return true;   // 表示限流触发
        }
        return false;      // 未超限，正常放行
    }
}
