package com.ticketflow.service.strategy.impl;

import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.enums.ProgramOrderVersion;
import com.ticketflow.initialize.impl.composite.CompositeContainer;
import com.ticketflow.lock.LockTask;
import com.ticketflow.service.ProgramOrderService;
import com.ticketflow.service.domain.CreateOrderTemporaryData;
import com.ticketflow.service.strategy.BaseProgramOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.ticketflow.core.DistributedLockConstants.PROGRAM_ORDER_CREATE_V4;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V4 下单策略的编排测试。
 * <p>
 * 重点是<b>顺序</b>：缓存预热必须在进入票档本地锁之前完成。
 * 座位缓存是一个票档两万个 field 的全量 Hash，冷缓存时加载要读整张座位表，
 * 放在锁内做会把第一批请求的锁占满，后面所有请求在 tryLock(3s) 上排队失败（70005）——
 * 而冷缓存恰恰就是开票第一波流量。
 */
@ExtendWith(MockitoExtension.class)
class ProgramOrderV4StrategyTest {

    @Mock
    private ProgramOrderService programOrderService;

    @Mock
    private BaseProgramOrder baseProgramOrder;

    @Mock
    private CompositeContainer compositeContainer;

    @InjectMocks
    private ProgramOrderV4Strategy programOrderV4Strategy;

    private static final String ORDER_NUMBER = "1001";

    @Test
    void 缓存预热必须先于本地锁执行() {
        ProgramOrderCreateDto dto = new ProgramOrderCreateDto();
        CreateOrderTemporaryData temporaryData = new CreateOrderTemporaryData(1L, List.of());
        when(baseProgramOrder.localLockExecute(eq(PROGRAM_ORDER_CREATE_V4), any(ProgramOrderCreateDto.class), any()))
                .thenAnswer(invocation -> ((LockTask<?>) invocation.getArgument(2)).execute());
        when(programOrderService.createOrderOperateProgramCacheResolution(dto)).thenReturn(temporaryData);
        when(programOrderService.createNewAsyncAfterLock(dto, temporaryData, ProgramOrderVersion.V4_VERSION.getValue()))
                .thenReturn(ORDER_NUMBER);

        String orderNumber = programOrderV4Strategy.createOrder(dto);

        assertEquals(ORDER_NUMBER, orderNumber);
        InOrder inOrder = inOrder(programOrderService, baseProgramOrder);
        inOrder.verify(programOrderService).ensureProgramCacheReady(dto);
        // 进锁之后的第二步才是 Lua 扣减（这里通过执行被包装的 LockTask 来验证它在锁内）
        inOrder.verify(baseProgramOrder)
                .localLockExecute(eq(PROGRAM_ORDER_CREATE_V4), eq(dto), any(LockTask.class));
        inOrder.verify(programOrderService).createOrderOperateProgramCacheResolution(dto);
        inOrder.verify(programOrderService)
                .createNewAsyncAfterLock(dto, temporaryData, ProgramOrderVersion.V4_VERSION.getValue());
    }

    @Test
    void 拿不到本地锁时不发Kafka消息() {
        ProgramOrderCreateDto dto = new ProgramOrderCreateDto();
        // tryLock 3 秒拿不到锁 -> 抛 70005，后面的发消息不应该被执行
        when(baseProgramOrder.localLockExecute(eq(PROGRAM_ORDER_CREATE_V4), any(ProgramOrderCreateDto.class), any()))
                .thenThrow(new RuntimeException("70005"));

        assertThrowsRuntimeException(() -> programOrderV4Strategy.createOrder(dto));
        verify(programOrderService, never()).createNewAsyncAfterLock(any(), any(), any());
    }

    private void assertThrowsRuntimeException(Runnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("预期抛出异常，但没有");
        } catch (RuntimeException expected) {
            // 期望就是它
        }
    }
}
