package com.ticketflow.scheduletask;

import com.ticketflow.client.ProgramClient;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.ProgramRecordTaskListDto;
import com.ticketflow.observability.Metrics;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.service.OrderTaskService;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.util.ServiceLockTool;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对账任务的调度行为测试。
 * <p>
 * 重点是：它<b>不能因为上一轮还在跑就静默消失</b>。
 * 对账是最终一致性的最后一道兜底，被跳过时必须留下可告警的痕迹（result=skipped），
 * 而不是只在日志里留一条 warn。
 */
class ReconciliationTaskTest {

    private OrderTaskService orderTaskService;
    private ProgramClient programClient;
    private RedisCache redisCache;
    private MeterRegistry meterRegistry;
    private ServiceLockTool serviceLockTool;
    private RLock roundLock;
    private ReconciliationTask reconciliationTask;

    @BeforeEach
    void setUp() throws Exception {
        orderTaskService = mock(OrderTaskService.class);
        programClient = mock(ProgramClient.class);
        redisCache = mock(RedisCache.class);
        meterRegistry = mock(MeterRegistry.class);
        serviceLockTool = mock(ServiceLockTool.class);
        roundLock = mock(RLock.class);
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(mock(Counter.class));

        reconciliationTask = new ReconciliationTask();
        ReflectionTestUtils.setField(reconciliationTask, "orderTaskService", orderTaskService);
        ReflectionTestUtils.setField(reconciliationTask, "programClient", programClient);
        ReflectionTestUtils.setField(reconciliationTask, "redisCache", redisCache);
        ReflectionTestUtils.setField(reconciliationTask, "meterRegistry", meterRegistry);
        // 多实例互斥锁：默认拿得到，走正常对账链路
        ReflectionTestUtils.setField(reconciliationTask, "serviceLockTool", serviceLockTool);
        when(serviceLockTool.getLock(any(), anyString(), any())).thenReturn(roundLock);
        when(roundLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
    }

    @Test
    void 其它实例正在对账时本轮跳过并记指标() throws Exception {
        when(roundLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);

        reconciliationTask.reconciliationTask();

        verify(meterRegistry, timeout(3000)).counter(eq(Metrics.RECONCILIATION_TASK_TOTAL), eq(Metrics.RESULT),
                eq(Metrics.RESULT_SKIPPED));
        // 没拿到锁就根本不查数据
        verify(programClient, never()).select(any());
        // 也没解锁（因为没持有）
        verify(roundLock, never()).unlock();
    }

    @Test
    void 没有待对账记录时记为健康空跑() throws Exception {
        when(programClient.select(any(ProgramRecordTaskListDto.class))).thenReturn(ApiResponse.ok(List.of()));

        reconciliationTask.reconciliationTask();

        waitUntilCounted(Metrics.RESULT_EMPTY);
        verify(meterRegistry).counter(eq(Metrics.RECONCILIATION_TASK_TOTAL), eq(Metrics.RESULT),
                eq(Metrics.RESULT_EMPTY));
    }

    @Test
    void 上一轮还在跑时本轮跳过并记指标() throws Exception {
        CountDownLatch firstRoundStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstRound = new CountDownLatch(1);
        when(programClient.select(any(ProgramRecordTaskListDto.class))).thenAnswer(invocation -> {
            firstRoundStarted.countDown();
            releaseFirstRound.await(5, TimeUnit.SECONDS);
            return ApiResponse.ok(List.of());
        });

        // 第一轮：占住唯一的对账线程，卡在查记录这一步
        reconciliationTask.reconciliationTask();
        assertTrue(firstRoundStarted.await(5, TimeUnit.SECONDS), "第一轮对账没有起来");

        // 第二轮：单线程 + 不排队 -> 被拒绝，但不抛异常，而是记 result=skipped
        reconciliationTask.reconciliationTask();
        verify(meterRegistry).counter(eq(Metrics.RECONCILIATION_TASK_TOTAL), eq(Metrics.RESULT),
                eq(Metrics.RESULT_SKIPPED));

        releaseFirstRound.countDown();
    }

    private void waitUntilCounted(String result) throws InterruptedException {
        // 对账是异步执行的：等它把指标写出来（最多 3 秒），避免用 sleep 硬等
        for (int i = 0; i < 30; i++) {
            try {
                verify(meterRegistry).counter(eq(Metrics.RECONCILIATION_TASK_TOTAL), eq(Metrics.RESULT), eq(result));
                return;
            } catch (AssertionError notYet) {
                Thread.sleep(100);
            }
        }
        verify(meterRegistry).counter(eq(Metrics.RECONCILIATION_TASK_TOTAL), eq(Metrics.RESULT), eq(result));
    }
}
