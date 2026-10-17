package com.ticketflow.scheduletask;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ticketflow.constant.ProgramOrderConstant;
import com.ticketflow.dto.OrderCancelDto;
import com.ticketflow.entity.Order;
import com.ticketflow.enums.OrderStatus;
import com.ticketflow.mapper.OrderMapper;
import com.ticketflow.service.OrderService;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.util.ServiceLockTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnpaidOrderCancelTaskTest {

    private OrderService orderService;
    private OrderMapper orderMapper;
    private ServiceLockTool serviceLockTool;
    private RLock lock;
    private UnpaidOrderCancelTask task;

    private static final Long ORDER_NUMBER_1 = 1001L;
    private static final Long ORDER_NUMBER_2 = 1002L;

    @BeforeEach
    void setUp() throws Exception {
        orderService = mock(OrderService.class);
        orderMapper = mock(OrderMapper.class);
        serviceLockTool = mock(ServiceLockTool.class);
        lock = mock(RLock.class);

        when(serviceLockTool.getLock(eq(LockType.Reentrant), any(), any())).thenReturn(lock);
        when(lock.tryLock(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(true);

        task = new UnpaidOrderCancelTask();
        ReflectionTestUtils.setField(task, "orderService", orderService);
        ReflectionTestUtils.setField(task, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(task, "serviceLockTool", serviceLockTool);
    }

    @Test
    void runCancelTask_success() throws Exception {
        Order order1 = new Order();
        order1.setOrderNumber(ORDER_NUMBER_1);
        order1.setOrderStatus(OrderStatus.NO_PAY.getCode());

        Order order2 = new Order();
        order2.setOrderNumber(ORDER_NUMBER_2);
        order2.setOrderStatus(OrderStatus.NO_PAY.getCode());

        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(order1, order2));
        when(orderService.cancel(any(OrderCancelDto.class))).thenReturn(true);

        task.runCancelTask();

        verify(orderService, timeout(2000)).cancel(org.mockito.Mockito.argThat(dto -> dto.getOrderNumber().equals(ORDER_NUMBER_1)));
        verify(orderService, timeout(2000)).cancel(org.mockito.Mockito.argThat(dto -> dto.getOrderNumber().equals(ORDER_NUMBER_2)));
        verify(lock, timeout(2000)).unlock();
    }

    @Test
    void runCancelTask_empty_returnsEarly() throws Exception {
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());

        task.runCancelTask();

        verify(orderService, never()).cancel(any(OrderCancelDto.class));
        verify(lock, timeout(2000)).unlock();
    }

    @Test
    void runCancelTask_lockFailed_skips() throws Exception {
        when(lock.tryLock(eq(0L), eq(TimeUnit.SECONDS))).thenReturn(false);

        task.runCancelTask();

        verify(orderMapper, never()).selectList(any());
        verify(lock, never()).unlock();
    }

    @Test
    void timeoutWindow_isDerivedFromSharedConstantAndLaterThanNormalPath() {
        int expectedWindow = (int) (ProgramOrderConstant.DELAY_ORDER_CANCEL_TIME_UNIT
                .toMinutes(ProgramOrderConstant.DELAY_ORDER_CANCEL_TIME)
                + ProgramOrderConstant.ORDER_CANCEL_FALLBACK_TOLERANCE_MINUTES);

        assertEquals(expectedWindow, ReflectionTestUtils.getField(task, "TIMEOUT_MINUTES"),
                "兜底窗口必须由 ProgramOrderConstant 派生，不得硬编码为第二套超时口径");
        assertTrue(expectedWindow > ProgramOrderConstant.DELAY_ORDER_CANCEL_TIME,
                "兜底窗口必须晚于延迟队列的正常关单时长，否则会与延迟队列重复关单");
    }

    @Test
    void runCancelTask_singleFailureDoesNotBlockSubsequentOrders() throws Exception {
        Order order1 = new Order();
        order1.setOrderNumber(ORDER_NUMBER_1);

        Order order2 = new Order();
        order2.setOrderNumber(ORDER_NUMBER_2);

        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(order1, order2));
        when(orderService.cancel(org.mockito.Mockito.argThat(dto -> dto != null && java.util.Objects.equals(dto.getOrderNumber(), ORDER_NUMBER_1))))
                .thenThrow(new RuntimeException("Simulated cancel error"));
        when(orderService.cancel(org.mockito.Mockito.argThat(dto -> dto != null && java.util.Objects.equals(dto.getOrderNumber(), ORDER_NUMBER_2))))
                .thenReturn(true);

        task.processTimeoutOrders();

        verify(orderService).cancel(org.mockito.Mockito.argThat(dto -> dto != null && java.util.Objects.equals(dto.getOrderNumber(), ORDER_NUMBER_1)));
        verify(orderService).cancel(org.mockito.Mockito.argThat(dto -> dto != null && java.util.Objects.equals(dto.getOrderNumber(), ORDER_NUMBER_2)));
    }
}
