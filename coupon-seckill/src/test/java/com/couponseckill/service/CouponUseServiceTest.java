package com.couponseckill.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.couponseckill.common.BizException;
import com.couponseckill.common.ErrorCode;
import com.couponseckill.config.RedisKeys;
import com.couponseckill.dto.CouponOperateResult;
import com.couponseckill.entity.UserCoupon;
import com.couponseckill.mapper.UserCouponMapper;
import com.couponseckill.support.MpTableInit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用券服务单元测试：锁券（Redis 闸门 + DB 乐观锁）/核销/退回 的全部分支。
 */
@ExtendWith(MockitoExtension.class)
class CouponUseServiceTest {

    @Mock
    private UserCouponMapper couponMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private CouponUseService couponUseService;

    private final Long userId = 100L;

    @BeforeAll
    static void initMp() {
        MpTableInit.init();
    }

    @BeforeEach
    void setUp() {
        // lenient：部分用例不使用 opsForValue()，避免 Mockito 严格模式报多余 stub
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private UserCoupon unusedCoupon() {
        UserCoupon c = new UserCoupon();
        c.setId(1L);
        c.setCouponNo("CP-1");
        c.setUserId(userId);
        c.setAmount(new BigDecimal("20.00"));
        c.setMinAmount(new BigDecimal("100.00"));
        c.setStatus(UserCoupon.STATUS_UNUSED);
        return c;
    }

    @Test
    @DisplayName("锁券成功：SETNX 通过 + DB 乐观锁通过 → 返回面额快照")
    void lockSuccess() {
        when(couponMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(unusedCoupon());
        when(valueOperations.setIfAbsent(eq(RedisKeys.couponLock("CP-1")), eq("ORDER-1"), eq(30L), eq(TimeUnit.MINUTES)))
                .thenReturn(true);
        when(couponMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        CouponOperateResult r = couponUseService.lockCoupon("CP-1", userId, "ORDER-1");

        assertEquals("CP-1", r.getCouponNo());
        assertEquals(0, new BigDecimal("20.00").compareTo(r.getAmount()));
    }

    @Test
    @DisplayName("锁券失败：Redis 锁已被占 → 50203，不触碰 DB")
    void lockRedisOccupied() {
        when(couponMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(unusedCoupon());
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Long.class), any(TimeUnit.class)))
                .thenReturn(false);

        BizException e = assertThrows(BizException.class,
                () -> couponUseService.lockCoupon("CP-1", userId, "ORDER-2"));
        assertEquals(ErrorCode.COUPON_ALREADY_LOCKED, e.getErrorCode());
        verify(couponMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("锁券失败：DB 乐观锁未命中（券已用/已锁）→ 50202 并释放自身 Redis 锁")
    void lockDbOptimisticFail() {
        when(couponMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(unusedCoupon());
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Long.class), any(TimeUnit.class)))
                .thenReturn(true);
        when(couponMapper.update(any(), any(Wrapper.class))).thenReturn(0);
        when(valueOperations.get(RedisKeys.couponLock("CP-1"))).thenReturn("ORDER-3");

        BizException e = assertThrows(BizException.class,
                () -> couponUseService.lockCoupon("CP-1", userId, "ORDER-3"));
        assertEquals(ErrorCode.COUPON_NOT_AVAILABLE, e.getErrorCode());
        // 仅当锁仍属于本订单才释放
        verify(redisTemplate).delete(RedisKeys.couponLock("CP-1"));
    }

    @Test
    @DisplayName("核销成功：已锁定→已使用 并释放 Redis 锁")
    void useSuccess() {
        when(couponMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        couponUseService.useCoupon("CP-1", userId, "ORDER-1");

        verify(redisTemplate).delete(RedisKeys.couponLock("CP-1"));
    }

    @Test
    @DisplayName("核销失败：状态/订单不匹配 → 50205，不释放锁")
    void useMismatch() {
        when(couponMapper.update(any(), any(Wrapper.class))).thenReturn(0);

        BizException e = assertThrows(BizException.class,
                () -> couponUseService.useCoupon("CP-1", userId, "ORDER-X"));
        assertEquals(ErrorCode.COUPON_ORDER_MISMATCH, e.getErrorCode());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("退回成功：已锁定→未使用 并释放 Redis 锁")
    void returnSuccess() {
        when(couponMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        couponUseService.returnCoupon("CP-1", userId, "ORDER-1");

        verify(redisTemplate).delete(RedisKeys.couponLock("CP-1"));
    }

    @Test
    @DisplayName("退回失败：订单不匹配 → 50205")
    void returnMismatch() {
        when(couponMapper.update(any(), any(Wrapper.class))).thenReturn(0);

        assertThrows(BizException.class, () -> couponUseService.returnCoupon("CP-1", userId, "ORDER-X"));
    }
}
