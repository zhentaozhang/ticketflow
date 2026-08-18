package com.couponseckill.id;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 雪花 ID 生成器单元测试：唯一性 / 单调递增 / 并发安全 / 时间戳可解析。
 */
class SnowflakeIdGeneratorTest {

    @Test
    @DisplayName("批量生成 10 万个 ID 无重复且单调递增")
    void uniqueAndIncreasing() {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator();
        int n = 100_000;
        long prev = -1;
        Set<Long> seen = new HashSet<>(n);
        for (int i = 0; i < n; i++) {
            long id = gen.nextId();
            assertTrue(seen.add(id), "重复 ID: " + id);
            assertTrue(id > prev, "必须单调递增: " + id + " <= " + prev);
            prev = id;
        }
        assertEquals(n, seen.size());
    }

    @Test
    @DisplayName("8 线程并发生成无重复")
    void concurrentUnique() throws Exception {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator();
        int threads = 8;
        int perThread = 10_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<Long> all = java.util.Collections.synchronizedSet(new HashSet<>());
        AtomicLong errors = new AtomicLong();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (!all.add(gen.nextId())) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发生成超时");
        pool.shutdown();

        assertEquals(0, errors.get(), "并发下出现重复 ID");
        assertEquals(threads * perThread, all.size());
    }

    @Test
    @DisplayName("ID 中可解析出时间戳（用于对账/排查）")
    void timestampExtractable() {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator();
        long before = System.currentTimeMillis();
        long id = gen.nextId();
        long after = System.currentTimeMillis();
        long ts = gen.extractTimestamp(id);
        assertTrue(ts >= before && ts <= after + 5, "时间戳应在生成时刻附近: " + ts);
    }

    @Test
    @DisplayName("字符串形式与数字形式一致")
    void strForm() {
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator();
        String s = gen.nextIdStr();
        assertEquals(s, Long.toString(Long.parseLong(s)), "nextIdStr 应输出可解析的 long 字符串");
    }
}
