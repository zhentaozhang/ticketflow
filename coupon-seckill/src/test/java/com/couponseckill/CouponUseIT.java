package com.couponseckill;

import com.couponseckill.common.BizException;
import com.couponseckill.common.ErrorCode;
import com.couponseckill.config.ShardingContext;
import com.couponseckill.dto.CouponOperateResult;
import com.couponseckill.dto.GrabRequest;
import com.couponseckill.dto.GrabResult;
import com.couponseckill.entity.CouponTemplate;
import com.couponseckill.entity.FlashSaleActivity;
import com.couponseckill.entity.FlashSaleOrder;
import com.couponseckill.entity.UserCoupon;
import com.couponseckill.id.SnowflakeIdGenerator;
import com.couponseckill.mapper.CouponTemplateMapper;
import com.couponseckill.mapper.FlashSaleActivityMapper;
import com.couponseckill.mapper.FlashSaleOrderMapper;
import com.couponseckill.mapper.UserCouponMapper;
import com.couponseckill.service.CouponUseService;
import com.couponseckill.service.FlashSaleGrabService;
import com.couponseckill.service.FlashSaleQueryService;
import com.couponseckill.service.ManageService;
import com.couponseckill.task.ReconcileTask;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用券闭环 + 对账集成测试。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CouponUseIT {

    @Autowired
    private ManageService manageService;
    @Autowired
    private FlashSaleGrabService grabService;
    @Autowired
    private FlashSaleQueryService queryService;
    @Autowired
    private CouponUseService couponUseService;
    @Autowired
    private CouponTemplateMapper templateMapper;
    @Autowired
    private FlashSaleActivityMapper activityMapper;
    @Autowired
    private FlashSaleOrderMapper orderMapper;
    @Autowired
    private UserCouponMapper couponMapper;
    @Autowired
    private SnowflakeIdGenerator idGenerator;
    @Autowired
    private ReconcileTask reconcileTask;
    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    private Long templateId;

    @BeforeAll
    void setupTemplate() {
        CouponTemplate t = new CouponTemplate();
        t.setId(idGenerator.nextId());
        t.setTemplateNo("TPL" + idGenerator.nextIdStr());
        t.setName("用券测试券");
        t.setType(CouponTemplate.TYPE_FULL_REDUCTION);
        t.setAmount(new BigDecimal("20.00"));
        t.setMinAmount(new BigDecimal("100.00"));
        t.setValidType(CouponTemplate.VALID_TYPE_DAYS);
        t.setValidDays(30);
        t.setScope(0);
        t.setStatus(CouponTemplate.STATUS_ENABLED);
        t.setCreateTime(LocalDateTime.now());
        t.setUpdateTime(LocalDateTime.now());
        templateMapper.insert(t);
        templateId = t.getId();
    }

    private FlashSaleActivity newActivity(int stock, int limit) {
        var req = new com.couponseckill.dto.CreateActivityRequest();
        req.setCouponTemplateId(templateId);
        req.setActivityName("用券测试活动-" + UUID.randomUUID());
        req.setStartTime(LocalDateTime.now().minusMinutes(5));
        req.setEndTime(LocalDateTime.now().plusHours(2));
        req.setTotalStock(stock);
        req.setPerUserLimit(limit);
        FlashSaleActivity activity = manageService.createActivity(req);
        manageService.publish(activity.getId());
        return activity;
    }

    /** 抢购并等待发券，返回券号 */
    private String grabCoupon(Long userId) {
        FlashSaleActivity activity = newActivity(10, 10);
        GrabRequest req = new GrabRequest();
        req.setActivityId(activity.getId());
        req.setRequestId(UUID.randomUUID().toString());
        grabService.grab(userId, req);
        awaitAsyncSettle(5_000);
        GrabResult result = queryService.queryResult(userId, activity.getId());
        assertEquals("SUCCESS", result.getGrabStatus());
        return result.getCouponNo();
    }

    @Test
    @DisplayName("用券闭环：锁券→核销→已使用")
    void lockThenUse() {
        long userId = 40001L;
        String couponNo = grabCoupon(userId);

        CouponOperateResult locked = couponUseService.lockCoupon(couponNo, userId, "ORDER-1");
        assertNotNull(locked);
        assertEquals(0, new BigDecimal("20.00").compareTo(locked.getAmount()));

        // 已锁定状态
        UserCoupon c = couponUseService.getCoupon(couponNo, userId);
        assertEquals(UserCoupon.STATUS_LOCKED, c.getStatus());

        couponUseService.useCoupon(couponNo, userId, "ORDER-1");
        c = couponUseService.getCoupon(couponNo, userId);
        assertEquals(UserCoupon.STATUS_USED, c.getStatus());
    }

    @Test
    @DisplayName("锁券后核销失败可退回，退回后可再次锁定")
    void lockReturnRelock() {
        long userId = 40002L;
        String couponNo = grabCoupon(userId);

        couponUseService.lockCoupon(couponNo, userId, "ORDER-2");
        couponUseService.returnCoupon(couponNo, userId, "ORDER-2");

        UserCoupon c = couponUseService.getCoupon(couponNo, userId);
        assertEquals(UserCoupon.STATUS_UNUSED, c.getStatus());

        // 再次锁定成功
        couponUseService.lockCoupon(couponNo, userId, "ORDER-3");
        assertEquals(UserCoupon.STATUS_LOCKED, couponUseService.getCoupon(couponNo, userId).getStatus());
    }

    @Test
    @DisplayName("并发锁券：同一券仅一个订单能锁定（防双花）")
    void concurrentLockSingleWinner() throws Exception {
        long userId = 40003L;
        String couponNo = grabCoupon(userId);

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger locked = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final String orderNo = "ORDER-C-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    couponUseService.lockCoupon(couponNo, userId, orderNo);
                    locked.incrementAndGet();
                } catch (Exception expected) {
                    // 锁竞争失败/中断：正常
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, locked.get(), "同一券只能被一个订单锁定");
        UserCoupon c = couponUseService.getCoupon(couponNo, userId);
        assertEquals(UserCoupon.STATUS_LOCKED, c.getStatus());
    }

    @Test
    @DisplayName("核销状态不匹配：已使用券再次核销失败")
    void useTwiceFails() {
        long userId = 40004L;
        String couponNo = grabCoupon(userId);

        couponUseService.lockCoupon(couponNo, userId, "ORDER-4");
        couponUseService.useCoupon(couponNo, userId, "ORDER-4");

        assertThrows(BizException.class, () -> couponUseService.useCoupon(couponNo, userId, "ORDER-4"));
        // 核销后不能退回
        assertThrows(BizException.class, () -> couponUseService.returnCoupon(couponNo, userId, "ORDER-4"));
    }

    @Test
    @DisplayName("对账：Redis 库存被篡改后，对账任务以 DB 为准修正")
    void reconcileFixesRedisStock() {
        FlashSaleActivity activity = newActivity(5, 1);
        long userId = 40005L;
        GrabRequest req = new GrabRequest();
        req.setActivityId(activity.getId());
        req.setRequestId(UUID.randomUUID().toString());
        grabService.grab(userId, req);
        awaitAsyncSettle(5_000);

        // 篡改 Redis 库存为错误值（模拟 Redis 数据错乱）
        redisTemplate.opsForValue().set("flash:stock:" + activity.getId(), "999");

        // 手动触发对账
        reconcileTask.reconcileStock();

        String fixed = redisTemplate.opsForValue().get("flash:stock:" + activity.getId());
        assertEquals("4", fixed, "对账后 Redis 库存 = 总库存5 - 已发券1 = 4");
    }

    private void awaitAsyncSettle(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
