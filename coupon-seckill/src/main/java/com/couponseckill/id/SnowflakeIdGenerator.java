package com.couponseckill.id;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 轻量雪花 ID 生成器（单机进程内安全）。
 * 位分配：1bit 符号 + 41bit 毫秒时间戳 + 10bit 机器/进程 + 12bit 序列。
 * 集成阶段替换为 ticketflow-id-generator-framework。
 */
@Component
public class SnowflakeIdGenerator {

    /** 纪元：2024-01-01 00:00:00 UTC */
    private static final long EPOCH = 1704067200000L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long WORKER_BITS = 10L;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;
    private static final long WORKER_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_BITS;

    private final long workerId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator() {
        // 进程内取 workerId：以进程 PID 的低 10 位 + 启动随机，单机多实例部署时由环境变量覆盖
        long pid = ProcessHandle.current().pid();
        String envWorker = System.getenv("COUPON_WORKER_ID");
        this.workerId = envWorker != null ? Long.parseLong(envWorker) : (pid & ((1L << WORKER_BITS) - 1));
    }

    public synchronized long nextId() {
        long now = System.currentTimeMillis();
        if (now < lastTimestamp) {
            // 时钟回拨：退避等待
            long offset = lastTimestamp - now;
            if (offset > 5000) {
                throw new IllegalStateException("clock moved backwards too much: " + offset + "ms");
            }
            now = waitUntil(lastTimestamp);
        }
        if (now == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                now = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = now;
        return ((now - EPOCH) << TIMESTAMP_SHIFT) | (workerId << WORKER_SHIFT) | sequence;
    }

    private long waitUntil(long target) {
        long now = System.currentTimeMillis();
        while (now <= target) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for clock", e);
            }
            now = System.currentTimeMillis();
        }
        return now;
    }

    /** 解析 ID 中的时间戳（毫秒，UTC 纪元），用于调试/对账 */
    public long extractTimestamp(long id) {
        return (id >>> TIMESTAMP_SHIFT) + EPOCH;
    }

    /** 便捷方法：生成字符串形式 ID */
    public String nextIdStr() {
        return Long.toString(nextId());
    }
}
