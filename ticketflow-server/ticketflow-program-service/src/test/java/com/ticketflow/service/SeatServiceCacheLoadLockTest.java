package com.ticketflow.service;

import com.ticketflow.core.DistributedLockConstants;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.mapper.SeatMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.util.ServiceLockTool;
import com.ticketflow.vo.SeatVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 座位缓存重建的"等锁上限"测试。
 * <p>
 * 场景：缓存未命中、正要重建，而保护重建的那把锁被别的请求拿着且对方卡住了。
 * 期望：<b>有界等待 → 再查一次缓存 → 仍没有就快速失败</b>；
 * 既不能无限等（请求线程会成片堆在锁上），也不能改成无锁重建（会把数据库一起拖垮）。
 */
class SeatServiceCacheLoadLockTest {

    private SeatService seatService;
    private RedisCache redisCache;
    private SeatMapper seatMapper;
    private ServiceLockTool serviceLockTool;
    private RLock lock;

    private static final Long PROGRAM_ID = 10L;
    private static final Long TICKET_CATEGORY_ID = 200L;
    private static final Long EXPIRY_SECONDS = 60L;

    @BeforeEach
    void setUp() {
        redisCache = mock(RedisCache.class);
        seatMapper = mock(SeatMapper.class);
        serviceLockTool = mock(ServiceLockTool.class);
        lock = mock(RLock.class);

        // 用 spy 是为了能把"缓存读取"这一步单独桩掉，专注验证等锁超时的分支
        seatService = spy(new SeatService());
        ReflectionTestUtils.setField(seatService, "redisCache", redisCache);
        ReflectionTestUtils.setField(seatService, "seatMapper", seatMapper);
        ReflectionTestUtils.setField(seatService, "serviceLockTool", serviceLockTool);
        when(serviceLockTool.getLock(eq(LockType.Reentrant), eq(DistributedLockConstants.GET_SEAT_LOCK),
                any(String[].class))).thenReturn(lock);
    }

    @Test
    void 等锁超时且缓存仍为空时快速失败() {
        when(serviceLockTool.tryLock(any(RLock.class), anyString())).thenReturn(false);
        // 快路径一次、超时后再查一次，都是空
        doReturn(List.of()).when(seatService).getSeatVoListByCacheResolution(anyLong(), anyLong());

        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> seatService.selectSeatResolution(PROGRAM_ID, TICKET_CATEGORY_ID, EXPIRY_SECONDS, TimeUnit.SECONDS));

        assertEquals(BaseCode.CACHE_LOAD_LOCK_TIMEOUT.getCode(), e.getCode());
        // 没有去读库重建：等不到锁时无锁重建会把数据库也拖下水
        verify(seatMapper, never()).selectList(any());
        // 也没写缓存
        verify(redisCache, never()).putHash(any(), any());
        // 更关键：没拿到锁就绝不能解锁（否则会把别人的锁解开）
        verify(lock, never()).unlock();
    }

    @Test
    void 等锁超时但缓存刚好被填好时直接返回() {
        when(serviceLockTool.tryLock(any(RLock.class), anyString())).thenReturn(false);
        SeatVo seatVo = new SeatVo();
        seatVo.setId(3000L);
        doReturn(List.of()).doReturn(List.of(seatVo))
                .when(seatService).getSeatVoListByCacheResolution(anyLong(), anyLong());

        List<SeatVo> result = seatService.selectSeatResolution(PROGRAM_ID, TICKET_CATEGORY_ID, EXPIRY_SECONDS,
                TimeUnit.SECONDS);

        assertEquals(1, result.size());
        assertEquals(3000L, result.get(0).getId());
        verify(seatMapper, never()).selectList(any());
        verify(lock, never()).unlock();
    }

    @Test
    void 拿到锁时走原来的重建链路() {
        when(serviceLockTool.tryLock(any(RLock.class), anyString())).thenReturn(true);
        SeatVo seatVo = new SeatVo();
        seatVo.setId(3000L);
        seatVo.setSellStatus(com.ticketflow.enums.SellStatus.NO_SOLD.getCode());
        seatVo.setTicketCategoryId(TICKET_CATEGORY_ID);
        seatVo.setPrice(java.math.BigDecimal.TEN);
        doReturn(List.of()).doReturn(List.of()).doReturn(List.of(seatVo))
                .when(seatService).getSeatVoListByCacheResolution(anyLong(), anyLong());
        when(seatMapper.selectList(any())).thenReturn(List.of());

        List<SeatVo> result = seatService.selectSeatResolution(PROGRAM_ID, TICKET_CATEGORY_ID, EXPIRY_SECONDS,
                TimeUnit.SECONDS);

        assertEquals(0, result.size());
        verify(seatMapper).selectList(any());
        verify(lock).unlock();
    }
}
