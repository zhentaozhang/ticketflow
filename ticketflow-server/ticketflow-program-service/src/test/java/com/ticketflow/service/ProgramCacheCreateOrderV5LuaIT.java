package com.ticketflow.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.core.SpringUtil;
import com.ticketflow.redis.RedisKeyBuild;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 集成测试（IT）：programDataCreateOrderResolutionV5.lua 的限购（50009）行为。
 * 纯 Redis Testcontainers（不起 MySQL/Kafka），真实执行 V5 Lua 脚本，验证：
 *   A. 超限（count=1, limit=1, 再购 1）→ 返回 50009，计数器不累加，幂等标记被 DEL（可重试）
 *   B. 未超限（count=0, limit=2, 购 1）→ 返回 0，计数器累加为 1
 *   C. 计数器未预热（GET nil）→ 视为 0 放行首单，计数器创建并钉 TTL（86400s）
 * 运行：mvn -pl ticketflow-server/ticketflow-program-service verify -Dit.test=ProgramCacheCreateOrderV5LuaIT
 */
@Testcontainers
@ExtendWith(MockitoExtension.class)
class ProgramCacheCreateOrderV5LuaIT {

    private static final long PROGRAM_ID = 501L;
    private static final long TICKET_CATEGORY_ID = 5L;
    private static final long USER_ID = 888L;
    private static final long SEAT_ID = 50L;
    private static final long TICKET_USER_ID = 4001L;
    /** 幂等标记 TTL（与 ProgramOrderService.V5_IDEMPOTENT_TTL_SECONDS 一致） */
    private static final String IDEMPOTENT_TTL_SECONDS = "3";

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    private StringRedisTemplate redisTemplate;
    private DefaultRedisScript<String> script;
    private LettuceConnectionFactory factory;

    @BeforeEach
    void setUp() {
        mockSpringUtilPrefix();
        factory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(factory);
        redisTemplate.afterPropertiesSet();
        script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/programDataCreateOrderResolutionV5.lua")));
        script.setResultType(String.class);
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @AfterEach
    void tearDown() {
        // 释放 lettuce 连接，避免测试 JVM 线程泄漏
        factory.destroy();
    }

    /** 与 ProgramOrderServiceTest.mockSpringUtil 一致：prefix=test，保证 RedisKeyBuild 生成 test- 前缀 key */
    private static void mockSpringUtilPrefix() {
        ConfigurableApplicationContext mockContext = mock(ConfigurableApplicationContext.class);
        ConfigurableEnvironment mockEnv = mock(ConfigurableEnvironment.class);
        lenient().when(mockContext.getEnvironment()).thenReturn(mockEnv);
        lenient().when(mockEnv.getProperty(eq("prefix.distinction.name"), anyString())).thenReturn("test");
        ReflectionTestUtils.setField(SpringUtil.class, "configurableApplicationContext", mockContext);
    }

    @Test
    void 超限时返回50009且不累加计数并删除幂等标记() {
        seedSeatAndRemain();
        String accountCountKey = accountCountKey();
        redisTemplate.opsForValue().set(accountCountKey, "1");  // 已购 1 单
        String idempotentKey = idempotentKey();

        JSONObject result = execute(1, "1");  // limit=1, 购 1

        assertEquals(50009, result.getIntValue("code"));
        assertEquals("1", redisTemplate.opsForValue().get(accountCountKey));  // 未累加
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(idempotentKey)));  // 幂等标记已 DEL，可重试
    }

    @Test
    void 未超限时正常扣减并累加计数() {
        seedSeatAndRemain();
        String accountCountKey = accountCountKey();
        redisTemplate.opsForValue().set(accountCountKey, "0");

        JSONObject result = execute(2, "1");  // limit=2, 购 1

        assertEquals(0, result.getIntValue("code"));
        assertEquals("1", redisTemplate.opsForValue().get(accountCountKey));  // 0 + 1
    }

    @Test
    void 计数器未预热时放行首单并创建带TTL的计数() {
        seedSeatAndRemain();
        String accountCountKey = accountCountKey();  // 不预置 → GET nil → 视为 0

        JSONObject result = execute(1, "1");  // limit=1, 购 1

        assertEquals(0, result.getIntValue("code"));
        assertEquals("1", redisTemplate.opsForValue().get(accountCountKey));  // 新建 = 1
        Long ttl = redisTemplate.getExpire(accountCountKey, TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 80000, "计数器应钉 86400s TTL，实际 TTL=" + ttl);  // 防止随 preload 短 TTL 过期重置 / 无 TTL 永久漂移
    }

    // ==================== 辅助 ====================

    /** 预置余票 hash（field=ticketCategoryId → "10"）与 no_sold 座位 hash（field=seatId → SeatVo JSON） */
    private void seedSeatAndRemain() {
        String remainKey = RedisKeyBuild.createRedisKey(
                RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION, PROGRAM_ID, TICKET_CATEGORY_ID).getRelKey();
        redisTemplate.opsForHash().put(remainKey, String.valueOf(TICKET_CATEGORY_ID), "10");
        String seatKey = RedisKeyBuild.createRedisKey(
                RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, PROGRAM_ID, TICKET_CATEGORY_ID).getRelKey();
        redisTemplate.opsForHash().put(seatKey, String.valueOf(SEAT_ID), seatVoJson());
    }

    private String seatVoJson() {
        return "{\"id\":" + SEAT_ID + ",\"ticketCategoryId\":" + TICKET_CATEGORY_ID
                + ",\"price\":2000,\"sellStatus\":1,\"rowCode\":1,\"colCode\":1}";
    }

    private String accountCountKey() {
        return RedisKeyBuild.createRedisKey(RedisKeyManage.ACCOUNT_ORDER_COUNT, USER_ID, PROGRAM_ID).getRelKey();
    }

    private String idempotentKey() {
        return RedisKeyBuild.createRedisKey(RedisKeyManage.V5_ORDER_CREATE_IDEMPOTENT, USER_ID, PROGRAM_ID).getRelKey();
    }

    /**
     * 组装 KEYS[1..9]/ARGV[1..6]（与 ProgramOrderService.createOrderOperateProgramCacheResolutionV5 一致）并执行 Lua。
     *
     * @param limit       ARGV[5] 限购数量
     * @param ticketCount ARGV[6] 本次购票总数
     */
    private JSONObject execute(int limit, String ticketCount) {
        String remainKey = RedisKeyBuild.createRedisKey(
                RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION, PROGRAM_ID, TICKET_CATEGORY_ID).getRelKey();
        String seatKey = RedisKeyBuild.createRedisKey(
                RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, PROGRAM_ID, TICKET_CATEGORY_ID).getRelKey();

        List<String> keys = new ArrayList<>();
        keys.add("1");  // KEYS[1] type=1 选座
        keys.add(RedisKeyBuild.getRedisKey(RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH));  // KEYS[2] 占位符
        keys.add(RedisKeyBuild.getRedisKey(RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH));     // KEYS[3] 占位符
        keys.add(String.valueOf(PROGRAM_ID));                                                      // KEYS[4]
        keys.add(RedisKeyBuild.getRedisKey(RedisKeyManage.PROGRAM_RECORD));                        // KEYS[5] 占位符
        keys.add("reduce_" + 777L + "_" + USER_ID);                                                // KEYS[6] 记录标识
        keys.add("reduce");                                                                        // KEYS[7] 记录类型
        keys.add(idempotentKey());                                                                 // KEYS[8] 幂等 key
        keys.add(accountCountKey());                                                               // KEYS[9] 计数 key

        String ticketCategoryListJson = JSON.toJSONString(List.of(
                new TicketCategoryCountArg(TICKET_CATEGORY_ID, Integer.parseInt(ticketCount), remainKey)));
        String seatDataJson = JSON.toJSONString(List.of(
                new SeatDataArg(seatKey, JSON.toJSONString(List.of(new SeatDtoArg(SEAT_ID, 2000, TICKET_CATEGORY_ID))))));
        String ticketUserIdListJson = JSON.toJSONString(List.of(String.valueOf(TICKET_USER_ID)));

        String[] args = new String[]{ticketCategoryListJson, seatDataJson, ticketUserIdListJson,
                IDEMPOTENT_TTL_SECONDS, String.valueOf(limit), ticketCount};

        String result = redisTemplate.execute(script, keys, args);
        assertNotNull(result);
        return JSON.parseObject(result);
    }

    // fastjson 序列化辅助（与 ProgramOrderService 组装的 JSON 结构一致）
    public static class TicketCategoryCountArg {
        public long ticketCategoryId;
        public int ticketCount;
        public String programTicketRemainNumberHashKey;

        public TicketCategoryCountArg(long ticketCategoryId, int ticketCount, String programTicketRemainNumberHashKey) {
            this.ticketCategoryId = ticketCategoryId;
            this.ticketCount = ticketCount;
            this.programTicketRemainNumberHashKey = programTicketRemainNumberHashKey;
        }
    }

    public static class SeatDataArg {
        public String seatNoSoldHashKey;
        public String seatDataList;

        public SeatDataArg(String seatNoSoldHashKey, String seatDataList) {
            this.seatNoSoldHashKey = seatNoSoldHashKey;
            this.seatDataList = seatDataList;
        }
    }

    public static class SeatDtoArg {
        public long id;
        public long price;
        public long ticketCategoryId;

        public SeatDtoArg(long id, long price, long ticketCategoryId) {
            this.id = id;
            this.price = price;
            this.ticketCategoryId = ticketCategoryId;
        }
    }
}
