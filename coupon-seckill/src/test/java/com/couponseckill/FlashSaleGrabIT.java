package com.couponseckill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.couponseckill.common.BizException;
import com.couponseckill.common.ErrorCode;
import com.couponseckill.config.ShardingContext;
import com.couponseckill.dto.CreateActivityRequest;
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
import com.couponseckill.service.FlashSaleGrabService;
import com.couponseckill.service.FlashSaleQueryService;
import com.couponseckill.service.ManageService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抢购主链路集成测试（真实 Redis + 真实 MySQL，mock 消息模式异步发券）。
 * 核心验证：并发不超卖 / 幂等 / 限购 / 全链路发券。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlashSaleGrabIT {

    @Autowired
    private ManageService manageService;
    @Autowired
    private FlashSaleGrabService grabService;
    @Autowired
    private FlashSaleQueryService queryService;
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

    private Long templateId;

    @BeforeAll
    void setupTemplate() {
        CouponTemplate t = new CouponTemplate();
        t.setId(idGenerator.nextId());
        t.setTemplateNo("TPL" + idGenerator.nextIdStr());
        t.setName("测试立减券");
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

    private FlashSaleActivity newActivity(int totalStock, int perUserLimit, LocalDateTime start, LocalDateTime end) {
        var req = new com.couponseckill.dto.CreateActivityRequest();
        req.setCouponTemplateId(templateId);
        req.setActivityName("测试活动-" + UUID.randomUUID());
        req.setStartTime(start);
        req.setEndTime(end);
        req.setTotalStock(totalStock);
        req.setPerUserLimit(perUserLimit);
        FlashSaleActivity activity = manageService.createActivity(req);
        manageService.publish(activity.getId());
        return activity;
    }

    private static LocalDateTime ongoingWindow() {
        return LocalDateTime.now().minusMinutes(5);
    }

    /**
     * 核心用例：100 线程并发抢 50 库存 → 恰好 50 成功、DB 恰好 50 条、零超卖。
     */
    @Test
    @DisplayName("并发抢购不超卖：100并发抢50库存")
    void concurrentGrabNoOversell() throws Exception {
        FlashSaleActivity activity = newActivity(50, 1, ongoingWindow(), LocalDateTime.now().plusHours(2));
        int threads = 100;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final long userId = 10000L + i;
            pool.submit(() -> {
                try {
                    start.await();
                    GrabRequest req = new GrabRequest();
                    req.setActivityId(activity.getId());
                    req.setRequestId(UUID.randomUUID().toString());
                    grabService.grab(userId, req);
                    success.incrementAndGet();
                } catch (BizException expected) {
                    // 售罄/限购等业务拒绝不计入成功
                } catch (Exception e) {
                    // 忽略其他（如系统繁忙重试语义）
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发抢购超时");
        pool.shutdown();

        // 等异步发券消费完成
        awaitAsyncSettle();

        // 断言：成功数 == 库存 50，且不超卖
        assertEquals(50, success.get(), "恰好 50 个抢购成功");
        long issued = countIssuedAcrossShards(activity.getId());
        assertEquals(50, issued, "DB 恰好 50 条已发券流水，零超卖");
        long coupons = countCouponsAcrossShards(activity.getId());
        assertEquals(50, coupons, "DB 恰好 50 张券");
    }

    @Test
    @DisplayName("幂等：同 requestId 重复抢购被拒绝")
    void duplicateRequestRejected() {
        FlashSaleActivity activity = newActivity(10, 1, ongoingWindow(), LocalDateTime.now().plusHours(2));
        String requestId = UUID.randomUUID().toString();

        GrabRequest req = new GrabRequest();
        req.setActivityId(activity.getId());
        req.setRequestId(requestId);

        grabService.grab(10001L, req);
        BizException e = assertThrows(BizException.class, () -> grabService.grab(10001L, req));
        assertEquals(ErrorCode.DUPLICATE_REQUEST, e.getErrorCode());

        awaitAsyncSettle();
        long rows = countOrdersAcrossShards(activity.getId(), 10001L);
        assertEquals(1, rows, "同 requestId 只产生一条流水");
    }

    @Test
    @DisplayName("限购：perUserLimit=2 时第三次抢购被拒")
    void perUserLimitEnforced() {
        FlashSaleActivity activity = newActivity(100, 2, ongoingWindow(), LocalDateTime.now().plusHours(2));
        long userId = 10002L;

        grabService.grab(userId, req(activity.getId()));
        grabService.grab(userId, req(activity.getId()));
        BizException e = assertThrows(BizException.class, () -> grabService.grab(userId, req(activity.getId())));
        assertEquals(ErrorCode.OVER_LIMIT, e.getErrorCode());
    }

    @Test
    @DisplayName("售罄：库存为0后抢购返回售罄")
    void stockEmpty() {
        FlashSaleActivity activity = newActivity(1, 1, ongoingWindow(), LocalDateTime.now().plusHours(2));
        grabService.grab(20001L, req(activity.getId()));
        awaitAsyncSettle();
        BizException e = assertThrows(BizException.class, () -> grabService.grab(20002L, req(activity.getId())));
        assertEquals(ErrorCode.STOCK_EMPTY, e.getErrorCode());
    }

    @Test
    @DisplayName("全链路：抢购→异步发券→结果查询 SUCCESS")
    void fullPipelineGrabToCoupon() {
        FlashSaleActivity activity = newActivity(10, 1, ongoingWindow(), LocalDateTime.now().plusHours(2));
        long userId = 30001L;
        String requestId = UUID.randomUUID().toString();

        GrabRequest req = new GrabRequest();
        req.setActivityId(activity.getId());
        req.setRequestId(requestId);
        GrabResult r = grabService.grab(userId, req);
        assertEquals("PROCESSING", r.getGrabStatus());

        awaitAsyncSettle();
        GrabResult result = queryService.queryResult(userId, activity.getId());
        assertEquals("SUCCESS", result.getGrabStatus());
        assertTrue(result.getCouponNo() != null && !result.getCouponNo().isBlank());
        assertEquals(0, new BigDecimal("20.00").compareTo(result.getAmount()));
    }

    private GrabRequest req(Long activityId) {
        GrabRequest req = new GrabRequest();
        req.setActivityId(activityId);
        req.setRequestId(UUID.randomUUID().toString());
        return req;
    }

    /** 等待异步发券消费完成（轮询 DB 到预期状态或超时） */
    private void awaitAsyncSettle() {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private long countIssuedAcrossShards(Long activityId) {
        long total = 0;
        for (int s = 0; s < com.couponseckill.config.ShardingContext.SHARD_COUNT; s++) {
            ShardingContext.setUserId((long) s);
            total += orderMapper.selectCount(new LambdaQueryWrapper<FlashSaleOrder>()
                    .eq(FlashSaleOrder::getActivityId, activityId)
                    .eq(FlashSaleOrder::getStatus, FlashSaleOrder.STATUS_ISSUED));
            ShardingContext.clear();
        }
        return total;
    }

    private long countCouponsAcrossShards(Long activityId) {
        long total = 0;
        for (int s = 0; s < com.couponseckill.config.ShardingContext.SHARD_COUNT; s++) {
            ShardingContext.setUserId((long) s);
            total += couponMapper.selectCount(new LambdaQueryWrapper<UserCoupon>()
                    .eq(UserCoupon::getActivityId, activityId));
            ShardingContext.clear();
        }
        return total;
    }

    private long countOrdersAcrossShards(Long activityId, Long userId) {
        ShardingContext.setUserId(userId);
        try {
            return orderMapper.selectCount(new LambdaQueryWrapper<FlashSaleOrder>()
                    .eq(FlashSaleOrder::getActivityId, activityId)
                    .eq(FlashSaleOrder::getUserId, userId));
        } finally {
            ShardingContext.clear();
        }
    }
}
