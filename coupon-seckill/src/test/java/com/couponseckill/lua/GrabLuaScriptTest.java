package com.couponseckill.lua;

import com.couponseckill.config.RedisKeys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lua 脚本行为测试（直连本地 Redis，不启动 Spring 容器）：
 * 用真实 Redis 验证 grab.lua 的时间窗 / 幂等 / 限购 / 库存边界，以及 rollback.lua 回补。
 * 无本地 Redis 时自动跳过（assumeTrue）。
 */
class GrabLuaScriptTest {

    private static StringRedisTemplate redisTemplate;
    private static DefaultRedisScript<Long> grabScript;
    private static DefaultRedisScript<Long> rollbackScript;

    private final long activityId = ThreadLocalRandom.current().nextLong(1_000_000, 2_000_000);
    private final long startTs = System.currentTimeMillis() - 60_000;
    private final long endTs = System.currentTimeMillis() + 3_600_000;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration("localhost", 6379);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(cfg);
        factory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(factory);
        redisTemplate.afterPropertiesSet();
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
        } catch (Exception e) {
            redisTemplate = null;
        }

        grabScript = new DefaultRedisScript<>();
        grabScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/grab.lua")));
        grabScript.setResultType(Long.class);

        rollbackScript = new DefaultRedisScript<>();
        rollbackScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/rollback.lua")));
        rollbackScript.setResultType(Long.class);
    }

    @AfterAll
    static void close() {
        if (redisTemplate != null && redisTemplate.getConnectionFactory() != null) {
            // 关闭连接工厂
            ((LettuceConnectionFactory) redisTemplate.getConnectionFactory()).destroy();
        }
    }

    private void assumeRedis() {
        assumeTrue(redisTemplate != null, "本地 Redis 不可用，跳过 Lua 脚本测试");
    }

    /** 初始化活动：meta + 库存 */
    private void warmUp(long stock, long limit) {
        redisTemplate.opsForValue().set(RedisKeys.meta(activityId),
                "{\"startTs\":" + startTs + ",\"endTs\":" + endTs + ",\"limit\":" + limit + "}");
        redisTemplate.opsForValue().set(RedisKeys.stock(activityId), String.valueOf(stock));
    }

    private void cleanup() {
        redisTemplate.delete(RedisKeys.meta(activityId));
        redisTemplate.delete(RedisKeys.stock(activityId));
    }

    private List<String> keys(long userId, String requestId) {
        return List.of(
                RedisKeys.stock(activityId),
                RedisKeys.limit(activityId, userId),
                RedisKeys.meta(activityId),
                RedisKeys.dedup(activityId, userId, requestId));
    }

    private long grab(long userId, String requestId) {
        return redisTemplate.execute(grabScript, keys(userId, requestId),
                String.valueOf(System.currentTimeMillis()), "60");
    }

    @Test
    @DisplayName("正常抢购返回 1，库存与限购计数正确递减")
    void grabSuccess() {
        assumeRedis();
        warmUp(10, 2);
        try {
            assertEquals(1L, grab(101L, UUID.randomUUID().toString()));
            assertEquals(1L, grab(101L, UUID.randomUUID().toString()));
            assertEquals("8", redisTemplate.opsForValue().get(RedisKeys.stock(activityId)));
            assertEquals("2", redisTemplate.opsForValue().get(RedisKeys.limit(activityId, 101L)));
        } finally {
            cleanup();
        }
    }

    @Test
    @DisplayName("库存耗尽返回 -1，且库存永不出现负值")
    void stockExhausted() {
        assumeRedis();
        warmUp(1, 10);
        try {
            assertEquals(1L, grab(201L, UUID.randomUUID().toString()));
            assertEquals(-1L, grab(202L, UUID.randomUUID().toString()));
            assertEquals(-1L, grab(203L, UUID.randomUUID().toString()));
            assertEquals("0", redisTemplate.opsForValue().get(RedisKeys.stock(activityId)),
                    "库存为负立即回加，永不出现负值");
        } finally {
            cleanup();
        }
    }

    @Test
    @DisplayName("超过限购返回 -2")
    void overLimit() {
        assumeRedis();
        warmUp(100, 2);
        try {
            assertEquals(1L, grab(301L, UUID.randomUUID().toString()));
            assertEquals(1L, grab(301L, UUID.randomUUID().toString()));
            assertEquals(-2L, grab(301L, UUID.randomUUID().toString()));
            assertEquals(98L, Long.parseLong(redisTemplate.opsForValue().get(RedisKeys.stock(activityId))),
                    "限购拦截不应扣库存");
        } finally {
            cleanup();
        }
    }

    @Test
    @DisplayName("同一 requestId 重复请求返回 -4（幂等）")
    void duplicateRequest() {
        assumeRedis();
        warmUp(10, 1);
        String requestId = UUID.randomUUID().toString();
        try {
            assertEquals(1L, grab(401L, requestId));
            assertEquals(-4L, grab(401L, requestId));
            assertEquals(-4L, grab(401L, requestId));
        } finally {
            cleanup();
        }
    }

    @Test
    @DisplayName("meta 缺失（活动未预热/已下架）返回 -3")
    void metaMissing() {
        assumeRedis();
        // 不预热 meta
        assertEquals(-3L, grab(501L, UUID.randomUUID().toString()));
        cleanup();
    }

    @Test
    @DisplayName("时间窗外（未开始/已结束）返回 -3")
    void outsideTimeWindow() {
        assumeRedis();
        redisTemplate.opsForValue().set(RedisKeys.meta(activityId),
                "{\"startTs\":" + (System.currentTimeMillis() + 3_600_000) + ",\"endTs\":"
                        + (System.currentTimeMillis() + 7_200_000) + ",\"limit\":1}");
        redisTemplate.opsForValue().set(RedisKeys.stock(activityId), "10");
        try {
            assertEquals(-3L, grab(601L, UUID.randomUUID().toString()), "未开始应拒绝");
        } finally {
            cleanup();
        }
    }

    @Test
    @DisplayName("rollback：回补库存与限购计数，清除幂等标记")
    void rollbackRestores() {
        assumeRedis();
        warmUp(10, 2);
        String requestId = UUID.randomUUID().toString();
        try {
            assertEquals(1L, grab(701L, requestId));
            assertEquals("9", redisTemplate.opsForValue().get(RedisKeys.stock(activityId)));
            assertEquals("1", redisTemplate.opsForValue().get(RedisKeys.limit(activityId, 701L)));

            // rollback.lua 布局与生产代码一致：KEYS=[stock, limit, dedup]
            redisTemplate.execute(rollbackScript, List.of(
                    RedisKeys.stock(activityId),
                    RedisKeys.limit(activityId, 701L),
                    RedisKeys.dedup(activityId, 701L, requestId)));
            assertEquals("10", redisTemplate.opsForValue().get(RedisKeys.stock(activityId)),
                    "回补后库存恢复");
            assertEquals("0", redisTemplate.opsForValue().get(RedisKeys.limit(activityId, 701L)),
                    "回补后限购计数归零");

            // 幂等标记已清除：同一 requestId 可再次成功（新的一次扣减）
            assertEquals(1L, grab(701L, requestId));
        } finally {
            cleanup();
        }
    }
}
