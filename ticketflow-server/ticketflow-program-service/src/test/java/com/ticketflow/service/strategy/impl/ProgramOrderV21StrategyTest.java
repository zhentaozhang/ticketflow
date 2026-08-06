package com.ticketflow.service.strategy.impl;

import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.dto.SeatDto;
import com.ticketflow.enums.ProgramOrderVersion;
import com.ticketflow.initialize.impl.composite.CompositeContainer;
import com.ticketflow.locallock.LocalLockCache;
import com.ticketflow.service.ProgramOrderService;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.util.ServiceLockTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static com.ticketflow.core.DistributedLockConstants.PROGRAM_ORDER_CREATE_V2;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProgramOrderV21StrategyTest {

    @Mock
    private ProgramOrderService programOrderService;

    @Mock
    private ServiceLockTool serviceLockTool;

    @Mock
    private CompositeContainer compositeContainer;

    @Mock
    private LocalLockCache localLockCache;

    @InjectMocks
    private ProgramOrderV21Strategy programOrderV21Strategy;

    @Test
    void createOrderShouldAcquireDistributedLockWithPrefixedNamePerTicketCategory() throws Exception {
        ProgramOrderCreateDto dto = new ProgramOrderCreateDto();
        dto.setUserId(1L);
        dto.setProgramId(10L);
        SeatDto seatDto = new SeatDto();
        seatDto.setId(1L);
        seatDto.setTicketCategoryId(22L);
        seatDto.setRowCode(1);
        seatDto.setColCode(1);
        seatDto.setPrice(new BigDecimal("100"));
        dto.setSeatDtoList(List.of(seatDto));

        RLock rLock = mock(RLock.class);
        when(rLock.tryLock(3L, TimeUnit.SECONDS)).thenReturn(true);
        when(serviceLockTool.getLock(eq(LockType.Reentrant), eq(PROGRAM_ORDER_CREATE_V2), any(String[].class)))
                .thenReturn(rLock);

        ReentrantLock localLock = new ReentrantLock(false);
        when(localLockCache.getLock(anyString(), eq(false))).thenReturn(localLock);

        when(programOrderService.create(dto, ProgramOrderVersion.V21_VERSION.getValue())).thenReturn("orderNumber");

        assertEquals("orderNumber", programOrderV21Strategy.createOrder(dto));

        verify(serviceLockTool).getLock(eq(LockType.Reentrant), eq(PROGRAM_ORDER_CREATE_V2),
                argThat(keys -> Arrays.equals(keys, new String[]{"10", "22"})));
        verify(serviceLockTool, never()).getLock(eq(LockType.Reentrant), anyString());
        verify(rLock).unlock();
    }
}