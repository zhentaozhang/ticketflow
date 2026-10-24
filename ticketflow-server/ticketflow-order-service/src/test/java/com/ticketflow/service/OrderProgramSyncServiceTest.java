package com.ticketflow.service;

import com.alibaba.fastjson.JSON;
import com.ticketflow.client.ProgramClient;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.core.SpringUtil;
import com.ticketflow.domain.OrderProgramSyncRecord;
import com.ticketflow.domain.ProgramSyncPayload;
import com.ticketflow.dto.ProgramOperateDataDto;
import com.ticketflow.dto.TicketCategoryCountDto;
import com.ticketflow.enums.ProgramOrderVersion;
import com.ticketflow.enums.SellStatus;
import com.ticketflow.mapper.OrderProgramSyncMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.vo.SeatSellStatusVo;
import com.ticketflow.vo.SeatVo;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 节目数据同步（outbox 执行端）测试。这里覆盖的是"Redis 与 DB 分叉"这条最危险的路径：
 * <ul>
 *   <li>Redis 没收敛 → 绝不能推 DB，只能退避（Redis 是权威源）；</li>
 *   <li>推送失败 → 先复核节目服务是否其实已经改成功（响应丢失不算失败）；</li>
 *   <li>反复失败 → 达上限转死信转人工，不无限重试；</li>
 *   <li>payload 损坏 → 直接死信，不让它每轮空转。</li>
 * </ul>
 */
class OrderProgramSyncServiceTest {

    private OrderProgramSyncService orderProgramSyncService;
    private OrderProgramSyncMapper orderProgramSyncMapper;
    private ProgramClient programClient;
    private RedisCache redisCache;
    private SimpleMeterRegistry meterRegistry;

    private static final Long ORDER_NUMBER = 1001L;
    private static final Long IDENTIFIER_ID = 99L;
    private static final Long PROGRAM_ID = 10L;
    private static final Long TICKET_CATEGORY_ID = 200L;
    private static final Long SEAT_ID = 3000L;

    @BeforeAll
    static void initSpringUtil() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        when(context.getEnvironment()).thenReturn(mock(ConfigurableEnvironment.class));
        new SpringUtil().initialize(context);
    }

    @AfterAll
    static void clearSpringUtil() {
        new SpringUtil().initialize(null);
    }

    @BeforeEach
    void setUp() {
        orderProgramSyncService = new OrderProgramSyncService();
        orderProgramSyncMapper = mock(OrderProgramSyncMapper.class);
        programClient = mock(ProgramClient.class);
        redisCache = mock(RedisCache.class);
        meterRegistry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(orderProgramSyncService, "orderProgramSyncMapper", orderProgramSyncMapper);
        ReflectionTestUtils.setField(orderProgramSyncService, "programClient", programClient);
        ReflectionTestUtils.setField(orderProgramSyncService, "redisCache", redisCache);
        ReflectionTestUtils.setField(orderProgramSyncService, "meterRegistry", meterRegistry);
    }

    private ProgramSyncPayload buildPayload(Integer sellStatus) {
        Map<Long, List<Long>> seatMap = new HashMap<>();
        seatMap.put(TICKET_CATEGORY_ID, List.of(SEAT_ID));
        return ProgramSyncPayload.of(PROGRAM_ID, ORDER_NUMBER, IDENTIFIER_ID, sellStatus,
                ProgramOrderVersion.V4_VERSION.getValue(), seatMap);
    }

    /**
     * 把 Redis 置成"已收敛"：LOCK 区没有该座位，目标区有该座位且状态正确。
     */
    private void stubRedisConverged(Integer sellStatus) {
        when(redisCache.getForHash(any(RedisKeyBuild.class), anyString(), eq(SeatVo.class))).thenAnswer(invocation -> {
            RedisKeyBuild redisKeyBuild = invocation.getArgument(0);
            String hashKey = invocation.getArgument(1);
            if (redisKeyBuild.getRelKey().contains("lock_resolution_hash")) {
                return null;
            }
            SeatVo seatVo = new SeatVo();
            seatVo.setId(Long.valueOf(hashKey));
            seatVo.setSellStatus(sellStatus);
            return seatVo;
        });
    }

    /**
     * 把 Redis 置成"未收敛"：座位还留在 LOCK 区（Lua 的 HDEL 没生效）。
     */
    private void stubRedisNotConverged() {
        when(redisCache.getForHash(any(RedisKeyBuild.class), anyString(), eq(SeatVo.class)))
                .thenReturn(new SeatVo());
    }

    private OrderProgramSyncRecord buildRecord(int attempts, String payloadJson) {
        OrderProgramSyncRecord record = new OrderProgramSyncRecord();
        record.setOrderNumber(ORDER_NUMBER);
        record.setAttempts(attempts);
        record.setPayload(payloadJson);
        return record;
    }

    private void stubSeatStatusApplied(Integer sellStatus) {
        SeatSellStatusVo seatSellStatusVo = new SeatSellStatusVo();
        seatSellStatusVo.setSeatId(SEAT_ID);
        seatSellStatusVo.setSellStatus(sellStatus);
        when(programClient.selectSeatSellStatus(any())).thenReturn(ApiResponse.ok(List.of(seatSellStatusVo)));
    }

    // ==================== 首次推送（事务提交后） ====================

    @Test
    void flushAfterCommitRedis已收敛且推送成功时标记已同步() {
        stubRedisConverged(SellStatus.SOLD.getCode());
        when(programClient.operateProgramData(any(ProgramOperateDataDto.class))).thenReturn(ApiResponse.ok(true));

        orderProgramSyncService.flushAfterCommit(buildPayload(SellStatus.SOLD.getCode()));

        verify(orderProgramSyncMapper).markDone(ORDER_NUMBER);
        verify(orderProgramSyncMapper, never()).markRetry(any(), any(), any(), any());
        verify(orderProgramSyncMapper, never()).markDead(any(), any(), any());
        assertEquals(1.0, meterRegistry.counter("ticketflow_order_program_sync_total", "result", "success").count());
    }

    @Test
    void flushAfterCommitRedis未收敛时不推进数据库只退避() {
        stubRedisNotConverged();

        orderProgramSyncService.flushAfterCommit(buildPayload(SellStatus.SOLD.getCode()));

        // 关键断言：Redis 是权威源，它没收敛就绝不能把 DB 推到前面
        verify(programClient, never()).operateProgramData(any());
        verify(orderProgramSyncMapper, never()).markDone(any());
        verify(orderProgramSyncMapper).markRetry(eq(ORDER_NUMBER), eq(1), any(Date.class), anyString());
        assertEquals(1.0,
                meterRegistry.counter("ticketflow_order_program_sync_total", "result", "not_converged").count());
    }

    @Test
    void flushAfterCommit推送失败但节目服务已生效时标记已同步() {
        stubRedisConverged(SellStatus.SOLD.getCode());
        when(programClient.operateProgramData(any(ProgramOperateDataDto.class)))
                .thenReturn(ApiResponse.error(500, "响应丢失"));
        stubSeatStatusApplied(SellStatus.SOLD.getCode());

        orderProgramSyncService.flushAfterCommit(buildPayload(SellStatus.SOLD.getCode()));

        verify(orderProgramSyncMapper).markDone(ORDER_NUMBER);
        verify(orderProgramSyncMapper, never()).markRetry(any(), any(), any(), any());
        assertEquals(1.0, meterRegistry.counter("ticketflow_order_program_sync_total", "result", "verified").count());
    }

    @Test
    void flushAfterCommit推送异常时不抛异常只登记重试() {
        stubRedisConverged(SellStatus.SOLD.getCode());
        when(programClient.operateProgramData(any(ProgramOperateDataDto.class)))
                .thenThrow(new RuntimeException("program服务不可用"));

        // 事务已经提交，这里绝不能再把异常抛回调用方
        orderProgramSyncService.flushAfterCommit(buildPayload(SellStatus.SOLD.getCode()));

        verify(orderProgramSyncMapper).markRetry(eq(ORDER_NUMBER), eq(1), any(Date.class), anyString());
        verify(orderProgramSyncMapper, never()).markDone(any());
    }

    // ==================== 重试任务 ====================

    @Test
    void syncDueOrders无到期记录时直接返回零且不调用节目服务() {
        when(orderProgramSyncMapper.selectDue(any(Date.class), anyInt())).thenReturn(new ArrayList<>());

        assertEquals(0, orderProgramSyncService.syncDueOrders());
        verify(programClient, never()).operateProgramData(any());
    }

    @Test
    void syncDueOrdersRedis未收敛时只退避不推送() {
        stubRedisNotConverged();
        when(orderProgramSyncMapper.selectDue(any(Date.class), anyInt())).thenReturn(
                List.of(buildRecord(1, JSON.toJSONString(buildPayload(SellStatus.SOLD.getCode())))));

        assertEquals(0, orderProgramSyncService.syncDueOrders());
        verify(programClient, never()).operateProgramData(any());
        // 第 2 次尝试失败：退避 60 秒
        verify(orderProgramSyncMapper).markRetry(eq(ORDER_NUMBER), eq(2), any(Date.class), anyString());
    }

    @Test
    void syncDueOrders推送成功后标记已同步() {
        stubRedisConverged(SellStatus.SOLD.getCode());
        when(orderProgramSyncMapper.selectDue(any(Date.class), anyInt())).thenReturn(
                List.of(buildRecord(1, JSON.toJSONString(buildPayload(SellStatus.SOLD.getCode())))));
        when(programClient.operateProgramData(any(ProgramOperateDataDto.class))).thenReturn(ApiResponse.ok(true));

        assertEquals(1, orderProgramSyncService.syncDueOrders());
        verify(orderProgramSyncMapper).markDone(ORDER_NUMBER);
    }

    @Test
    void syncDueOrders推送失败且未生效时登记重试() {
        stubRedisConverged(SellStatus.SOLD.getCode());
        when(orderProgramSyncMapper.selectDue(any(Date.class), anyInt())).thenReturn(
                List.of(buildRecord(2, JSON.toJSONString(buildPayload(SellStatus.SOLD.getCode())))));
        when(programClient.operateProgramData(any(ProgramOperateDataDto.class)))
                .thenReturn(ApiResponse.error(500, "失败"));
        when(programClient.selectSeatSellStatus(any())).thenReturn(ApiResponse.ok(List.of()));

        assertEquals(0, orderProgramSyncService.syncDueOrders());
        verify(orderProgramSyncMapper).markRetry(eq(ORDER_NUMBER), eq(3), any(Date.class), anyString());
        verify(orderProgramSyncMapper, never()).markDone(any());
    }

    @Test
    void syncDueOrders达到最大重试次数时转死信() {
        stubRedisConverged(SellStatus.SOLD.getCode());
        when(orderProgramSyncMapper.selectDue(any(Date.class), anyInt())).thenReturn(
                List.of(buildRecord(5, JSON.toJSONString(buildPayload(SellStatus.SOLD.getCode())))));
        when(programClient.operateProgramData(any(ProgramOperateDataDto.class)))
                .thenReturn(ApiResponse.error(500, "失败"));
        when(programClient.selectSeatSellStatus(any())).thenReturn(ApiResponse.ok(List.of()));

        assertEquals(0, orderProgramSyncService.syncDueOrders());
        verify(orderProgramSyncMapper).markDead(eq(ORDER_NUMBER), eq(6), anyString());
        verify(orderProgramSyncMapper, never()).markRetry(any(), any(), any(), any());
    }

    @Test
    void syncDueOrderspayload无法解析时直接转死信() {
        when(orderProgramSyncMapper.selectDue(any(Date.class), anyInt()))
                .thenReturn(List.of(buildRecord(1, "{不是合法json")));

        assertEquals(0, orderProgramSyncService.syncDueOrders());
        verify(orderProgramSyncMapper).markDead(eq(ORDER_NUMBER), anyInt(), anyString());
        verify(programClient, never()).operateProgramData(any());
    }

    // ==================== 差异扫描 ====================

    @Test
    void scanTerminalWithoutSync发现未登记同步的终态订单时上报() {
        when(orderProgramSyncMapper.selectTerminalWithoutSync(anyInt(), any(Date.class), any(Date.class), anyInt()))
                .thenReturn(List.of(ORDER_NUMBER));

        assertEquals(1, orderProgramSyncService.scanTerminalWithoutSync());
        assertEquals(1.0, meterRegistry.counter("ticketflow_order_program_sync_total", "result", "unsynced").count());
        // 这类订单没有 payload，无法自动重放，只能上报
        verify(programClient, never()).operateProgramData(any());
    }

    @Test
    void scanTerminalWithoutSync扫描窗口只覆盖最近两小时() {
        when(orderProgramSyncMapper.selectTerminalWithoutSync(anyInt(), any(Date.class), any(Date.class), anyInt()))
                .thenReturn(new ArrayList<>());

        assertEquals(0, orderProgramSyncService.scanTerminalWithoutSync());
        verify(orderProgramSyncMapper).selectTerminalWithoutSync(
                eq(ProgramOrderVersion.V4_VERSION.getValue()), any(Date.class), any(Date.class), eq(50));
    }

    // ==================== payload 与 DTO 转换 ====================

    @Test
    void payload序列化后能原样解析并保持座位分组() {
        ProgramSyncPayload payload = buildPayload(SellStatus.NO_SOLD.getCode());

        ProgramSyncPayload parsed = JSON.parseObject(JSON.toJSONString(payload), ProgramSyncPayload.class);

        assertNotNull(parsed);
        assertEquals(PROGRAM_ID, parsed.getProgramId());
        assertEquals(ORDER_NUMBER, parsed.getOrderNumber());
        assertEquals(IDENTIFIER_ID, parsed.getIdentifierId());
        assertEquals(SellStatus.NO_SOLD.getCode(), parsed.getSellStatus());
        assertEquals(List.of(SEAT_ID), parsed.getTicketCategorySeatMap().get(TICKET_CATEGORY_ID));
        assertEquals(1, parsed.getTicketCategoryCountDtoList().size());
        TicketCategoryCountDto countDto = parsed.getTicketCategoryCountDtoList().get(0);
        assertEquals(TICKET_CATEGORY_ID, countDto.getTicketCategoryId());
        assertEquals(1L, countDto.getCount());
        assertEquals(List.of(SEAT_ID), parsed.getAllSeatIdList());
    }

    @Test
    void buildProgramOperateDataDto使用订单标识作为节目服务幂等键() {
        ProgramOperateDataDto dto = OrderProgramSyncService.buildProgramOperateDataDto(
                buildPayload(SellStatus.SOLD.getCode()));

        assertEquals(PROGRAM_ID, dto.getProgramId());
        assertEquals(IDENTIFIER_ID, dto.getOrderNumber());
        assertEquals(SellStatus.SOLD.getCode(), dto.getSellStatus());
        assertEquals(ProgramOrderVersion.V4_VERSION.getValue(), dto.getOrderVersion());
        assertEquals(List.of(SEAT_ID), dto.getSeatIdList());
        assertFalse(dto.getSeatIdList().isEmpty());
        assertTrue(dto.getTicketCategoryCountDtoList().size() == 1);
    }
}
