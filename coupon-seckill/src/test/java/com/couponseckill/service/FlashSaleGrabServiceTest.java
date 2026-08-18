package com.couponseckill.service;

import com.couponseckill.common.BizException;
import com.couponseckill.common.ErrorCode;
import com.couponseckill.dto.GrabRequest;
import com.couponseckill.dto.GrabResult;
import com.couponseckill.entity.FlashSaleActivity;
import com.couponseckill.id.SnowflakeIdGenerator;
import com.couponseckill.kafka.FlashSaleMessagePublisher;
import com.couponseckill.kafka.FlashSaleRequestMessage;
import com.couponseckill.mapper.FlashSaleActivityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 抢购服务单元测试（Mockito 隔离，不依赖 Redis/DB）：
 * Lua 返回码 → 业务错误映射；publish 失败 → 回补。
 *
 * 说明：@InjectMocks 不会注入带 @Autowired 的非 final 字段（grabScript/rollbackScript），
 * 这里改为手动构造 + ReflectionTestUtils 注入。
 */
@ExtendWith(MockitoExtension.class)
class FlashSaleGrabServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private FlashSaleMessagePublisher messagePublisher;
    @Mock
    private FlashSaleActivityMapper activityMapper;
    @Mock
    private SnowflakeIdGenerator idGenerator;
    @Mock
    private ActivityMetaCache metaCache;
    @Mock
    private DefaultRedisScript<Long> grabScript;
    @Mock
    private DefaultRedisScript<Long> rollbackScript;

    private FlashSaleGrabService grabService;

    private final Long userId = 123L;

    @BeforeEach
    void assemble() {
        grabService = new FlashSaleGrabService(redisTemplate, messagePublisher, activityMapper, idGenerator, metaCache);
        ReflectionTestUtils.setField(grabService, "grabScript", grabScript);
        ReflectionTestUtils.setField(grabService, "rollbackScript", rollbackScript);

        // 预校验走本地缓存，避免依赖 DB mock
        ActivityMetaCache.ActivityMeta ongoing = new ActivityMetaCache.ActivityMeta(
                FlashSaleActivity.STATUS_ONGOING,
                System.currentTimeMillis() - 60_000,
                System.currentTimeMillis() + 3_600_000);
        when(metaCache.get(any())).thenReturn(ongoing);
    }

    private GrabRequest req() {
        GrabRequest req = new GrabRequest();
        req.setActivityId(1L);
        req.setRequestId("req-1");
        return req;
    }

    private void mockLuaResult(Long code) {
        // execute(script, keys, String now, String ttl)：varargs 用单元素匹配器
        when(redisTemplate.execute(eq(grabScript), anyList(), anyString(), anyString())).thenReturn(code);
    }

    @Test
    @DisplayName("成功：Lua=1 → 发消息 → 返回 PROCESSING")
    void grabSuccessPublishesAndReturnsProcessing() {
        mockLuaResult(1L);
        when(idGenerator.nextIdStr()).thenReturn("10086");

        GrabResult r = grabService.grab(userId, req());

        assertEquals("PROCESSING", r.getGrabStatus());
        ArgumentCaptor<FlashSaleRequestMessage> captor = ArgumentCaptor.forClass(FlashSaleRequestMessage.class);
        verify(messagePublisher).publish(captor.capture());
        assertEquals(1L, captor.getValue().getActivityId());
        assertEquals(userId, captor.getValue().getUserId());
        assertEquals("req-1", captor.getValue().getRequestId());
    }

    @Test
    @DisplayName("售罄：Lua=-1 → 50012，不发消息")
    void stockEmptyMapped() {
        mockLuaResult(-1L);
        BizException e = assertThrows(BizException.class, () -> grabService.grab(userId, req()));
        assertEquals(ErrorCode.STOCK_EMPTY, e.getErrorCode());
        verify(messagePublisher, never()).publish(any());
    }

    @Test
    @DisplayName("限购：Lua=-2 → 50013")
    void overLimitMapped() {
        mockLuaResult(-2L);
        BizException e = assertThrows(BizException.class, () -> grabService.grab(userId, req()));
        assertEquals(ErrorCode.OVER_LIMIT, e.getErrorCode());
    }

    @Test
    @DisplayName("重复：Lua=-4 → 50014")
    void duplicateMapped() {
        mockLuaResult(-4L);
        BizException e = assertThrows(BizException.class, () -> grabService.grab(userId, req()));
        assertEquals(ErrorCode.DUPLICATE_REQUEST, e.getErrorCode());
    }

    @Test
    @DisplayName("未开始：Lua=-3 且 DB 活动未开始 → 50010")
    void notStartedMappedFromDb() {
        mockLuaResult(-3L);
        FlashSaleActivity activity = new FlashSaleActivity();
        activity.setStatus(FlashSaleActivity.STATUS_NOT_STARTED);
        activity.setStartTime(LocalDateTime.now().plusHours(1));
        activity.setEndTime(LocalDateTime.now().plusHours(2));
        when(activityMapper.selectById(1L)).thenReturn(activity);

        BizException e = assertThrows(BizException.class, () -> grabService.grab(userId, req()));
        assertEquals(ErrorCode.ACTIVITY_NOT_STARTED, e.getErrorCode());
    }

    @Test
    @DisplayName("publish 失败：回补脚本被调用 + 返回系统繁忙")
    void publishFailureTriggersRollback() {
        mockLuaResult(1L);
        when(idGenerator.nextIdStr()).thenReturn("10086");
        doThrow(new IllegalStateException("kafka down"))
                .when(messagePublisher).publish(any(FlashSaleRequestMessage.class));

        BizException e = assertThrows(BizException.class, () -> grabService.grab(userId, req()));
        assertEquals(ErrorCode.SYSTEM_BUSY, e.getErrorCode());
        // rollback() 调用的是 execute(script, keys) 两参版本（无 varargs）
        verify(redisTemplate).execute(eq(rollbackScript), anyList());
    }

    @Test
    @DisplayName("Redis 异常：直接返回系统繁忙，绝不落库")
    void redisErrorReturnsBusy() {
        when(redisTemplate.execute(eq(grabScript), anyList(), anyString(), anyString()))
                .thenThrow(new RuntimeException("redis down"));
        BizException e = assertThrows(BizException.class, () -> grabService.grab(userId, req()));
        assertEquals(ErrorCode.SYSTEM_BUSY, e.getErrorCode());
        verify(messagePublisher, never()).publish(any());
    }

    @Test
    @DisplayName("预校验：活动已结束直接拒绝，不碰 Redis")
    void preCheckEndedWithoutRedis() {
        ActivityMetaCache.ActivityMeta ended = new ActivityMetaCache.ActivityMeta(
                FlashSaleActivity.STATUS_ENDED,
                System.currentTimeMillis() - 3_600_000,
                System.currentTimeMillis() - 60_000);
        when(metaCache.get(1L)).thenReturn(ended);

        BizException e = assertThrows(BizException.class, () -> grabService.grab(userId, req()));
        assertEquals(ErrorCode.ACTIVITY_ENDED, e.getErrorCode());
        verify(redisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString());
    }
}
