package com.ticketflow.service;

import com.alibaba.fastjson.JSON;
import com.ticketflow.client.ProgramClient;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.core.SpringUtil;
import com.ticketflow.domain.ProgramRecord;
import com.ticketflow.domain.ReconciliationTaskData;
import com.ticketflow.domain.SeatRecord;
import com.ticketflow.domain.TicketCategoryRecord;
import com.ticketflow.dto.TicketCategoryListDto;
import com.ticketflow.entity.OrderProgram;
import com.ticketflow.entity.OrderTicketUserRecord;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.HandleStatus;
import com.ticketflow.enums.RecordType;
import com.ticketflow.enums.ReconciliationStatus;
import com.ticketflow.enums.SellStatus;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.mapper.OrderProgramMapper;
import com.ticketflow.mapper.OrderTicketUserRecordMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.service.handler.ProgramRecordHandler;
import com.ticketflow.service.handler.SeatHandler;
import com.ticketflow.service.handler.TicketRemainNumberHandler;
import com.ticketflow.vo.TicketCategoryDetailVo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OrderTaskService 对账/恢复任务测试：
 * 1. reconciliationTask 主链路：无订单返回 null、无待补偿记录不调节目服务、
 *    缺失补偿（reduce/increase/changeStatus 状态映射）、部分缺失过滤、节目服务失败透传
 * 2. restoreSingleOrder 逆向还原算法：reduce/increase/changeStatus 的余票逆向计算、
 *    混合链路还原、多票档计算、未知票档兜底
 *
 * 说明：private 方法（findNeedCompensationRecords/compensateAndFinalize 等）通过
 * reconciliationTask 主链路间接覆盖；SpringUtil 静态容器需 @BeforeAll 初始化。
 */
class OrderTaskServiceTest {

    private OrderTaskService orderTaskService;
    private RedisCache redisCache;
    private OrderTicketUserRecordMapper orderTicketUserRecordMapper;
    private ProgramClient programClient;
    private ProgramRecordHandler programRecordHandler;
    private SeatHandler seatHandler;
    private TicketRemainNumberHandler ticketRemainNumberHandler;
    private OrderProgramMapper orderProgramMapper;

    private static final Long PROGRAM_ID = 10L;
    private static final Long IDENTIFIER_ID = 99L;
    private static final Long USER_ID = 1L;
    private static final Long ORDER_NUMBER = 1001L;
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
        orderTaskService = new OrderTaskService();
        redisCache = mock(RedisCache.class);
        orderTicketUserRecordMapper = mock(OrderTicketUserRecordMapper.class);
        programClient = mock(ProgramClient.class);
        programRecordHandler = mock(ProgramRecordHandler.class);
        seatHandler = mock(SeatHandler.class);
        ticketRemainNumberHandler = mock(TicketRemainNumberHandler.class);
        orderProgramMapper = mock(OrderProgramMapper.class);

        ReflectionTestUtils.setField(orderTaskService, "redisCache", redisCache);
        ReflectionTestUtils.setField(orderTaskService, "orderTicketUserRecordMapper", orderTicketUserRecordMapper);
        ReflectionTestUtils.setField(orderTaskService, "programClient", programClient);
        ReflectionTestUtils.setField(orderTaskService, "programRecordHandler", programRecordHandler);
        ReflectionTestUtils.setField(orderTaskService, "seatHandler", seatHandler);
        ReflectionTestUtils.setField(orderTaskService, "ticketRemainNumberHandler", ticketRemainNumberHandler);
        ReflectionTestUtils.setField(orderTaskService, "orderProgramMapper", orderProgramMapper);
    }

    // ==================== 构建辅助 ====================

    private TicketCategoryDetailVo buildTicketCategoryDetailVo() {
        TicketCategoryDetailVo vo = new TicketCategoryDetailVo();
        vo.setId(TICKET_CATEGORY_ID);
        vo.setRemainNumber(90L);
        return vo;
    }

    private OrderProgram buildOrderProgram() {
        OrderProgram orderProgram = new OrderProgram();
        orderProgram.setId(1L);
        orderProgram.setProgramId(PROGRAM_ID);
        orderProgram.setOrderNumber(ORDER_NUMBER);
        orderProgram.setIdentifierId(IDENTIFIER_ID);
        orderProgram.setHandleStatus(HandleStatus.NO_HANDLE.getCode());
        return orderProgram;
    }

    private OrderTicketUserRecord buildRecord(Long seatId, String recordTypeValue) {
        OrderTicketUserRecord record = new OrderTicketUserRecord();
        record.setId(seatId);
        record.setOrderNumber(ORDER_NUMBER);
        record.setIdentifierId(IDENTIFIER_ID);
        record.setUserId(USER_ID);
        record.setTicketUserId(seatId + 1000);
        record.setSeatId(seatId);
        record.setTicketCategoryId(TICKET_CATEGORY_ID);
        record.setRecordTypeValue(recordTypeValue);
        record.setRecordTypeCode(RecordType.getCodeByValue(recordTypeValue));
        record.setReconciliationStatus(ReconciliationStatus.RECONCILIATION_NO.getCode());
        return record;
    }

    private ProgramRecord buildProgramRecord(String recordType, Long... seatIds) {
        ProgramRecord programRecord = new ProgramRecord();
        programRecord.setRecordType(recordType);
        programRecord.setTimestamp(System.currentTimeMillis());
        TicketCategoryRecord tcr = new TicketCategoryRecord();
        tcr.setTicketCategoryId(TICKET_CATEGORY_ID);
        List<SeatRecord> seatRecordList = new ArrayList<>();
        for (Long seatId : seatIds) {
            SeatRecord seatRecord = new SeatRecord();
            seatRecord.setSeatId(seatId);
            seatRecord.setTicketCategoryId(TICKET_CATEGORY_ID);
            seatRecord.setTicketUserId(seatId + 1000);
            seatRecordList.add(seatRecord);
        }
        tcr.setSeatRecordList(seatRecordList);
        programRecord.setTicketCategoryRecordList(List.of(tcr));
        return programRecord;
    }

    private String buildRedisRecordJson(String recordType, Long... seatIds) {
        return JSON.toJSONString(buildProgramRecord(recordType, seatIds));
    }

    private String redisKey(String recordType) {
        return recordType + "_" + IDENTIFIER_ID + "_" + USER_ID;
    }

    private void stubReconciliationCommon() {
        when(orderProgramMapper.selectList(any())).thenReturn(List.of(buildOrderProgram()));
        when(orderTicketUserRecordMapper.selectList(any())).thenReturn(List.of());
        when(redisCache.getAllMapForHash(any(), eq(String.class))).thenReturn(new HashMap<>());
    }

    // ==================== reconciliationTask 主链路 ====================

    @Test
    void reconciliationTask无未处理订单时返回null() {
        when(orderProgramMapper.selectList(any())).thenReturn(List.of());
        ReconciliationTaskData result = orderTaskService.reconciliationTask(PROGRAM_ID);
        assertNull(result);
        verify(orderTicketUserRecordMapper, never()).selectList(any());
        verify(programRecordHandler, never()).add(anyInt(), any(), anyMap(), anyMap());
    }

    @Test
    void reconciliationTask无待对账记录时仍执行最终化且补偿为空() {
        stubReconciliationCommon();
        ReconciliationTaskData result = orderTaskService.reconciliationTask(PROGRAM_ID);
        assertNotNull(result);
        assertEquals(PROGRAM_ID, result.getProgramId());
        assertNotNull(result.getAddRedisRecordData());
        assertTrue(result.getAddRedisRecordData().isEmpty());
        verify(programClient, never()).selectList(any());
        verify(programRecordHandler).add(eq(0), eq(PROGRAM_ID), anyMap(), anyMap());
    }

    @Test
    void reconciliationTask数据库记录与Redis完全一致时不调用节目服务() {
        when(orderProgramMapper.selectList(any())).thenReturn(List.of(buildOrderProgram()));
        when(orderTicketUserRecordMapper.selectList(any()))
                .thenReturn(List.of(buildRecord(SEAT_ID, RecordType.REDUCE.getValue())));
        Map<String, String> redisRecordMap = new HashMap<>();
        redisRecordMap.put(redisKey(RecordType.REDUCE.getValue()), buildRedisRecordJson(RecordType.REDUCE.getValue(), SEAT_ID));
        when(redisCache.getAllMapForHash(any(), eq(String.class))).thenReturn(redisRecordMap);
        ReconciliationTaskData result = orderTaskService.reconciliationTask(PROGRAM_ID);
        assertTrue(result.getAddRedisRecordData().isEmpty());
        verify(programClient, never()).selectList(any());
        verify(seatHandler, never()).delRedisSeatData(any(), any());
        verify(programRecordHandler).add(eq(0), eq(PROGRAM_ID), anyMap(), eq(redisRecordMap));
    }

    @Test
    void reconciliationTaskreduce缺失时补偿并标记座位为未售到锁定() {
        when(orderProgramMapper.selectList(any())).thenReturn(List.of(buildOrderProgram()));
        when(orderTicketUserRecordMapper.selectList(any()))
                .thenReturn(List.of(buildRecord(SEAT_ID, RecordType.REDUCE.getValue())));
        when(redisCache.getAllMapForHash(any(), eq(String.class))).thenReturn(new HashMap<>());
        when(programClient.selectList(any(TicketCategoryListDto.class))).thenReturn(ApiResponse.ok(List.of(buildTicketCategoryDetailVo())));
        ReconciliationTaskData result = orderTaskService.reconciliationTask(PROGRAM_ID);

        assertEquals(1, result.getAddRedisRecordData().size());
        ProgramRecord programRecord = result.getAddRedisRecordData().get(redisKey(RecordType.REDUCE.getValue()));
        assertEquals(RecordType.REDUCE.getValue(), programRecord.getRecordType());
        SeatRecord seatRecord = programRecord.getTicketCategoryRecordList().get(0).getSeatRecordList().get(0);
        assertEquals(SEAT_ID, seatRecord.getSeatId());
        assertEquals(SellStatus.NO_SOLD.getCode(), seatRecord.getBeforeStatus());
        assertEquals(SellStatus.LOCK.getCode(), seatRecord.getAfterStatus());

        verify(programClient).selectList(any(TicketCategoryListDto.class));
        verify(seatHandler).delRedisSeatData(PROGRAM_ID, TICKET_CATEGORY_ID);
        verify(ticketRemainNumberHandler).delRedisSeatData(PROGRAM_ID, TICKET_CATEGORY_ID);
        ArgumentCaptor<Map<String, ProgramRecord>> captor = ArgumentCaptor.forClass(Map.class);
        verify(programRecordHandler).add(eq(0), eq(PROGRAM_ID), captor.capture(), anyMap());
        assertEquals(programRecord.getRecordType(), captor.getValue().get(redisKey(RecordType.REDUCE.getValue())).getRecordType());
    }

    @Test
    void reconciliationTaskincrease缺失时补偿并标记座位为锁定到未售() {
        when(orderProgramMapper.selectList(any())).thenReturn(List.of(buildOrderProgram()));
        when(orderTicketUserRecordMapper.selectList(any()))
                .thenReturn(List.of(buildRecord(SEAT_ID, RecordType.INCREASE.getValue())));
        when(redisCache.getAllMapForHash(any(), eq(String.class))).thenReturn(new HashMap<>());
        when(programClient.selectList(any(TicketCategoryListDto.class))).thenReturn(ApiResponse.ok(List.of(buildTicketCategoryDetailVo())));
        ReconciliationTaskData result = orderTaskService.reconciliationTask(PROGRAM_ID);
        SeatRecord seatRecord = result.getAddRedisRecordData().get(redisKey(RecordType.INCREASE.getValue()))
                .getTicketCategoryRecordList().get(0).getSeatRecordList().get(0);
        assertEquals(SellStatus.LOCK.getCode(), seatRecord.getBeforeStatus());
        assertEquals(SellStatus.NO_SOLD.getCode(), seatRecord.getAfterStatus());
    }

    @Test
    void reconciliationTaskchangeStatus缺失时补偿并标记座位为锁定到已售() {
        when(orderProgramMapper.selectList(any())).thenReturn(List.of(buildOrderProgram()));
        when(orderTicketUserRecordMapper.selectList(any()))
                .thenReturn(List.of(buildRecord(SEAT_ID, RecordType.CHANGE_STATUS.getValue())));
        when(redisCache.getAllMapForHash(any(), eq(String.class))).thenReturn(new HashMap<>());
        when(programClient.selectList(any(TicketCategoryListDto.class))).thenReturn(ApiResponse.ok(List.of(buildTicketCategoryDetailVo())));
        ReconciliationTaskData result = orderTaskService.reconciliationTask(PROGRAM_ID);
        SeatRecord seatRecord = result.getAddRedisRecordData().get(redisKey(RecordType.CHANGE_STATUS.getValue()))
                .getTicketCategoryRecordList().get(0).getSeatRecordList().get(0);
        assertEquals(SellStatus.LOCK.getCode(), seatRecord.getBeforeStatus());
        assertEquals(SellStatus.SOLD.getCode(), seatRecord.getAfterStatus());
    }

    @Test
    void reconciliationTask多种类型缺失时按记录类型排序() {
        when(orderProgramMapper.selectList(any())).thenReturn(List.of(buildOrderProgram()));
        when(orderTicketUserRecordMapper.selectList(any()))
                .thenReturn(List.of(
                        buildRecord(SEAT_ID + 1, RecordType.INCREASE.getValue()),
                        buildRecord(SEAT_ID, RecordType.REDUCE.getValue())));
        when(redisCache.getAllMapForHash(any(), eq(String.class))).thenReturn(new HashMap<>());
        when(programClient.selectList(any(TicketCategoryListDto.class))).thenReturn(ApiResponse.ok(List.of(buildTicketCategoryDetailVo())));
        ReconciliationTaskData result = orderTaskService.reconciliationTask(PROGRAM_ID);
        assertEquals(2, result.getAddRedisRecordData().size());
        assertTrue(result.getAddRedisRecordData().containsKey(redisKey(RecordType.REDUCE.getValue())));
        assertTrue(result.getAddRedisRecordData().containsKey(redisKey(RecordType.INCREASE.getValue())));
    }

    @Test
    void reconciliationTask同类型部分座位已存在Redis时只补偿缺失的() {
        when(orderProgramMapper.selectList(any())).thenReturn(List.of(buildOrderProgram()));
        when(orderTicketUserRecordMapper.selectList(any()))
                .thenReturn(List.of(
                        buildRecord(SEAT_ID, RecordType.REDUCE.getValue()),
                        buildRecord(SEAT_ID + 1, RecordType.REDUCE.getValue())));
        Map<String, String> redisRecordMap = new HashMap<>();
        redisRecordMap.put(redisKey(RecordType.REDUCE.getValue()), buildRedisRecordJson(RecordType.REDUCE.getValue(), SEAT_ID));
        when(redisCache.getAllMapForHash(any(), eq(String.class))).thenReturn(redisRecordMap);
        when(programClient.selectList(any(TicketCategoryListDto.class))).thenReturn(ApiResponse.ok(List.of(buildTicketCategoryDetailVo())));
        ReconciliationTaskData result = orderTaskService.reconciliationTask(PROGRAM_ID);
        List<SeatRecord> seatRecords = result.getAddRedisRecordData().get(redisKey(RecordType.REDUCE.getValue()))
                .getTicketCategoryRecordList().get(0).getSeatRecordList();
        assertEquals(1, seatRecords.size());
        assertEquals(SEAT_ID + 1, seatRecords.get(0).getSeatId());
    }

    @Test
    void reconciliationTask节目服务返回失败时抛异常() {
        when(orderProgramMapper.selectList(any())).thenReturn(List.of(buildOrderProgram()));
        when(orderTicketUserRecordMapper.selectList(any()))
                .thenReturn(List.of(buildRecord(SEAT_ID, RecordType.REDUCE.getValue())));
        when(redisCache.getAllMapForHash(any(), eq(String.class))).thenReturn(new HashMap<>());
        when(programClient.selectList(any(TicketCategoryListDto.class))).thenReturn(ApiResponse.error(8888, "节目服务失败"));
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderTaskService.reconciliationTask(PROGRAM_ID));
        assertEquals(8888, e.getCode());
        verify(programRecordHandler, never()).add(anyInt(), any(), anyMap(), anyMap());
    }

    @Test
    void reconciliationTask节目服务返回空列表时抛异常() {
        when(orderProgramMapper.selectList(any())).thenReturn(List.of(buildOrderProgram()));
        when(orderTicketUserRecordMapper.selectList(any()))
                .thenReturn(List.of(buildRecord(SEAT_ID, RecordType.REDUCE.getValue())));
        when(redisCache.getAllMapForHash(any(), eq(String.class))).thenReturn(new HashMap<>());
        when(programClient.selectList(any(TicketCategoryListDto.class))).thenReturn(ApiResponse.ok(new ArrayList<>()));
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderTaskService.reconciliationTask(PROGRAM_ID));
        assertEquals(BaseCode.RPC_RESULT_DATA_EMPTY.getCode(), e.getCode());
        verify(programRecordHandler, never()).add(anyInt(), any(), anyMap(), anyMap());
    }

    @Test
    void reconciliationTask节目服务返回data为null时抛异常() {
        when(orderProgramMapper.selectList(any())).thenReturn(List.of(buildOrderProgram()));
        when(orderTicketUserRecordMapper.selectList(any()))
                .thenReturn(List.of(buildRecord(SEAT_ID, RecordType.REDUCE.getValue())));
        when(redisCache.getAllMapForHash(any(), eq(String.class))).thenReturn(new HashMap<>());
        when(programClient.selectList(any(TicketCategoryListDto.class))).thenReturn(ApiResponse.ok(null));
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderTaskService.reconciliationTask(PROGRAM_ID));
        assertEquals(BaseCode.RPC_RESULT_DATA_EMPTY.getCode(), e.getCode());
        verify(programRecordHandler, never()).add(anyInt(), any(), anyMap(), anyMap());
    }

    @Test
    void reconciliationTask节目服务返回缺票档列表时抛异常() {
        when(orderProgramMapper.selectList(any())).thenReturn(List.of(buildOrderProgram()));
        when(orderTicketUserRecordMapper.selectList(any()))
                .thenReturn(List.of(buildRecord(SEAT_ID, RecordType.REDUCE.getValue())));
        when(redisCache.getAllMapForHash(any(), eq(String.class))).thenReturn(new HashMap<>());
        TicketCategoryDetailVo otherCategory = new TicketCategoryDetailVo();
        otherCategory.setId(TICKET_CATEGORY_ID + 100);
        otherCategory.setRemainNumber(90L);
        when(programClient.selectList(any(TicketCategoryListDto.class))).thenReturn(ApiResponse.ok(List.of(otherCategory)));
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderTaskService.reconciliationTask(PROGRAM_ID));
        assertEquals(BaseCode.RPC_RESULT_DATA_EMPTY.getCode(), e.getCode());
        verify(programRecordHandler, never()).add(anyInt(), any(), anyMap(), anyMap());
    }

    // ==================== restoreSingleOrder 逆向还原 ====================

    @Test
    void restoreSingleOrderreduce逆向还原加回余票() {
        List<ProgramRecord> programRecords = List.of(buildProgramRecord(RecordType.REDUCE.getValue(), SEAT_ID, SEAT_ID + 1));
        Map<Long, Long> remainMap = new HashMap<>();
        remainMap.put(TICKET_CATEGORY_ID, 90L);
        orderTaskService.restoreSingleOrder(programRecords, remainMap);
        TicketCategoryRecord tcr = programRecords.get(0).getTicketCategoryRecordList().get(0);
        assertEquals(92L, tcr.getBeforeAmount());
        assertEquals(90L, tcr.getAfterAmount());
        assertEquals(2L, tcr.getChangeAmount());
        assertEquals(92L, remainMap.get(TICKET_CATEGORY_ID));
    }

    @Test
    void restoreSingleOrderincrease逆向还原再扣减余票() {
        List<ProgramRecord> programRecords = List.of(buildProgramRecord(RecordType.INCREASE.getValue(), SEAT_ID, SEAT_ID + 1));
        Map<Long, Long> remainMap = new HashMap<>();
        remainMap.put(TICKET_CATEGORY_ID, 90L);
        orderTaskService.restoreSingleOrder(programRecords, remainMap);
        TicketCategoryRecord tcr = programRecords.get(0).getTicketCategoryRecordList().get(0);
        assertEquals(88L, tcr.getBeforeAmount());
        assertEquals(90L, tcr.getAfterAmount());
        assertEquals(2L, tcr.getChangeAmount());
        assertEquals(88L, remainMap.get(TICKET_CATEGORY_ID));
    }

    @Test
    void restoreSingleOrderchangeStatus不改余票数量() {
        List<ProgramRecord> programRecords = List.of(buildProgramRecord(RecordType.CHANGE_STATUS.getValue(), SEAT_ID));
        Map<Long, Long> remainMap = new HashMap<>();
        remainMap.put(TICKET_CATEGORY_ID, 90L);
        orderTaskService.restoreSingleOrder(programRecords, remainMap);
        TicketCategoryRecord tcr = programRecords.get(0).getTicketCategoryRecordList().get(0);
        assertEquals(90L, tcr.getBeforeAmount());
        assertEquals(90L, tcr.getAfterAmount());
        assertEquals(0L, tcr.getChangeAmount());
        assertEquals(90L, remainMap.get(TICKET_CATEGORY_ID));
    }

    @Test
    void restoreSingleOrder混合链路逆向还原余票链条正确() {
        List<ProgramRecord> programRecords = new ArrayList<>();
        programRecords.add(buildProgramRecord(RecordType.REDUCE.getValue(), SEAT_ID, SEAT_ID + 1));
        programRecords.add(buildProgramRecord(RecordType.CHANGE_STATUS.getValue(), SEAT_ID));
        programRecords.add(buildProgramRecord(RecordType.INCREASE.getValue(), SEAT_ID));
        Map<Long, Long> remainMap = new HashMap<>();
        remainMap.put(TICKET_CATEGORY_ID, 90L);
        orderTaskService.restoreSingleOrder(programRecords, remainMap);

        // 业务正序: 余票91 -> reduce 2张(91->89) -> changeStatus(89->89) -> increase 1张(89->90=当前)
        // 逆向还原(倒序): increase先撤销(90->89) -> changeStatus(89->89) -> reduce撤销(89->91)
        TicketCategoryRecord reduceTcr = programRecords.get(0).getTicketCategoryRecordList().get(0);
        assertEquals(91L, reduceTcr.getBeforeAmount());
        assertEquals(89L, reduceTcr.getAfterAmount());
        TicketCategoryRecord changeTcr = programRecords.get(1).getTicketCategoryRecordList().get(0);
        assertEquals(89L, changeTcr.getBeforeAmount());
        TicketCategoryRecord increaseTcr = programRecords.get(2).getTicketCategoryRecordList().get(0);
        assertEquals(89L, increaseTcr.getBeforeAmount());
        assertEquals(90L, increaseTcr.getAfterAmount());
        assertEquals(91L, remainMap.get(TICKET_CATEGORY_ID));
    }

    @Test
    void restoreSingleOrder多票档分别计算() {
        ProgramRecord programRecord = new ProgramRecord();
        programRecord.setRecordType(RecordType.REDUCE.getValue());
        TicketCategoryRecord tcrA = new TicketCategoryRecord();
        tcrA.setTicketCategoryId(TICKET_CATEGORY_ID);
        SeatRecord seatA = new SeatRecord();
        seatA.setSeatId(SEAT_ID);
        seatA.setTicketCategoryId(TICKET_CATEGORY_ID);
        tcrA.setSeatRecordList(List.of(seatA));
        TicketCategoryRecord tcrB = new TicketCategoryRecord();
        tcrB.setTicketCategoryId(TICKET_CATEGORY_ID + 1);
        SeatRecord seatB = new SeatRecord();
        seatB.setSeatId(SEAT_ID + 1);
        seatB.setTicketCategoryId(TICKET_CATEGORY_ID + 1);
        tcrB.setSeatRecordList(List.of(seatB, seatB));
        programRecord.setTicketCategoryRecordList(List.of(tcrA, tcrB));
        Map<Long, Long> remainMap = new HashMap<>();
        remainMap.put(TICKET_CATEGORY_ID, 90L);
        remainMap.put(TICKET_CATEGORY_ID + 1, 50L);
        orderTaskService.restoreSingleOrder(List.of(programRecord), remainMap);
        assertEquals(91L, programRecord.getTicketCategoryRecordList().get(0).getBeforeAmount());
        assertEquals(52L, programRecord.getTicketCategoryRecordList().get(1).getBeforeAmount());
    }

    @Test
    void restoreSingleOrder未知票档按零余票起算() {
        List<ProgramRecord> programRecords = List.of(buildProgramRecord(RecordType.REDUCE.getValue(), SEAT_ID));
        Map<Long, Long> remainMap = new HashMap<>();
        orderTaskService.restoreSingleOrder(programRecords, remainMap);
        TicketCategoryRecord tcr = programRecords.get(0).getTicketCategoryRecordList().get(0);
        assertEquals(1L, tcr.getBeforeAmount());
        assertEquals(0L, tcr.getAfterAmount());
        assertEquals(1L, remainMap.get(TICKET_CATEGORY_ID));
    }

    @Test
    void restoreSingleOrder还原后恢复原始记录顺序() {
        List<ProgramRecord> programRecords = new ArrayList<>();
        programRecords.add(buildProgramRecord(RecordType.REDUCE.getValue(), SEAT_ID));
        programRecords.add(buildProgramRecord(RecordType.INCREASE.getValue(), SEAT_ID));
        orderTaskService.restoreSingleOrder(programRecords, new HashMap<>());
        assertEquals(RecordType.REDUCE.getValue(), programRecords.get(0).getRecordType());
        assertEquals(RecordType.INCREASE.getValue(), programRecords.get(1).getRecordType());
    }
}
