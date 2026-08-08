package com.ticketflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baidu.fsg.uid.UidGenerator;
import com.ticketflow.client.BaseDataClient;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.AreaSelectDto;
import com.ticketflow.dto.ProgramAddDto;
import com.ticketflow.dto.ProgramListDto;
import com.ticketflow.dto.ProgramOperateDataDto;
import com.ticketflow.dto.ProgramPageListDto;
import com.ticketflow.dto.ProgramRecommendListDto;
import com.ticketflow.dto.ReduceRemainNumberDto;
import com.ticketflow.dto.TicketCategoryCountDto;
import com.ticketflow.entity.Program;
import com.ticketflow.entity.ProgramCategory;
import com.ticketflow.entity.ProgramJoinShowTime;
import com.ticketflow.entity.ProgramShowTime;
import com.ticketflow.entity.Seat;
import com.ticketflow.entity.TicketCategoryAggregate;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.ProgramOrderVersion;
import com.ticketflow.enums.SellStatus;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.initialize.impl.composite.CompositeContainer;
import com.ticketflow.mapper.ProgramCategoryMapper;
import com.ticketflow.mapper.ProgramMapper;
import com.ticketflow.mapper.ProgramShowTimeMapper;
import com.ticketflow.mapper.SeatMapper;
import com.ticketflow.mapper.TicketCategoryMapper;
import com.ticketflow.page.PageVo;
import com.ticketflow.service.constant.ProgramTimeType;
import com.ticketflow.service.es.ProgramEs;
import com.ticketflow.util.DateUtils;
import com.ticketflow.vo.AreaVo;
import com.ticketflow.vo.ProgramHomeVo;
import com.ticketflow.vo.ProgramListVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static com.ticketflow.util.DateUtils.FORMAT_DATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProgramService 事务核心方法测试：
 * 1. operateSeatLockAndTicketCategoryRemainNumber：抢票时锁定座位 + 扣减余票（防超卖）
 * 2. operateProgramData：支付/取消时座位状态流转（V1-V3 乐观 / V4 悲观 LOCK 校验）+ 库存归还
 */
class ProgramServiceTest {

    private ProgramService programService;
    private SeatMapper seatMapper;
    private TicketCategoryMapper ticketCategoryMapper;

    private static final Long PROGRAM_ID = 10L;
    private static final Long SEAT_ID_1 = 100L;
    private static final Long SEAT_ID_2 = 101L;
    private static final Long TICKET_CATEGORY_ID = 200L;

    @BeforeEach
    void setUp() {
        programService = new ProgramService();
        seatMapper = mock(SeatMapper.class);
        ticketCategoryMapper = mock(TicketCategoryMapper.class);
        ReflectionTestUtils.setField(programService, "seatMapper", seatMapper);
        ReflectionTestUtils.setField(programService, "ticketCategoryMapper", ticketCategoryMapper);
    }

    private Seat seat(Long id, Integer sellStatus) {
        Seat seat = new Seat();
        seat.setId(id);
        seat.setProgramId(PROGRAM_ID);
        seat.setSellStatus(sellStatus);
        return seat;
    }

    private TicketCategoryCountDto countDto(Long ticketCategoryId, Long count) {
        TicketCategoryCountDto dto = new TicketCategoryCountDto();
        dto.setTicketCategoryId(ticketCategoryId);
        dto.setCount(count);
        return dto;
    }

    private ReduceRemainNumberDto reduceDto(Integer sellStatus) {
        ReduceRemainNumberDto dto = new ReduceRemainNumberDto();
        dto.setProgramId(PROGRAM_ID);
        dto.setSeatIdList(List.of(SEAT_ID_1, SEAT_ID_2));
        dto.setSellStatus(sellStatus);
        dto.setTicketCategoryCountDtoList(List.of(countDto(TICKET_CATEGORY_ID, 2L)));
        return dto;
    }

    private ProgramOperateDataDto operateDataDto(Integer sellStatus, Integer orderVersion) {
        ProgramOperateDataDto dto = new ProgramOperateDataDto();
        dto.setProgramId(PROGRAM_ID);
        dto.setSeatIdList(List.of(SEAT_ID_1, SEAT_ID_2));
        dto.setSellStatus(sellStatus);
        dto.setOrderVersion(orderVersion);
        dto.setTicketCategoryCountDtoList(List.of(countDto(TICKET_CATEGORY_ID, 2L)));
        return dto;
    }

    // ==================== operateSeatLockAndTicketCategoryRemainNumber ====================

    @Nested
    class OperateSeatLockAndTicketCategoryRemainNumber {

        @Test
        void 座位不存在_抛异常() {
            when(seatMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> programService.operateSeatLockAndTicketCategoryRemainNumber(reduceDto(SellStatus.LOCK.getCode())));
            assertEquals(BaseCode.SEAT_NOT_EXIST.getCode(), ex.getCode());
            verify(seatMapper, never()).update(any(Seat.class), any(LambdaUpdateWrapper.class));
        }

        @Test
        void 座位数量与预设不一致_抛异常() {
            when(seatMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(seat(SEAT_ID_1, SellStatus.NO_SOLD.getCode())));

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> programService.operateSeatLockAndTicketCategoryRemainNumber(reduceDto(SellStatus.LOCK.getCode())));
            assertEquals(BaseCode.SEAT_UPDATE_REL_COUNT_NOT_EQUAL_PRESET_COUNT.getCode(), ex.getCode());
            verify(seatMapper, never()).update(any(Seat.class), any(LambdaUpdateWrapper.class));
        }

        @Test
        void 座位非未售_抛异常() {
            when(seatMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(seat(SEAT_ID_1, SellStatus.NO_SOLD.getCode()), seat(SEAT_ID_2, SellStatus.LOCK.getCode())));

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> programService.operateSeatLockAndTicketCategoryRemainNumber(reduceDto(SellStatus.LOCK.getCode())));
            assertEquals(BaseCode.SEAT_IS_NOT_NOT_SOLD.getCode(), ex.getCode());
            verify(seatMapper, never()).update(any(Seat.class), any(LambdaUpdateWrapper.class));
        }

        @Test
        void 成功_锁定座位并扣减库存() {
            when(seatMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(seat(SEAT_ID_1, SellStatus.NO_SOLD.getCode()), seat(SEAT_ID_2, SellStatus.NO_SOLD.getCode())));
            when(ticketCategoryMapper.reduceRemainNumber(anyLong(), anyLong(), anyLong())).thenReturn(1);

            Boolean result = programService.operateSeatLockAndTicketCategoryRemainNumber(reduceDto(SellStatus.LOCK.getCode()));

            assertTrue(result);
            verify(seatMapper).update(any(Seat.class), any(LambdaUpdateWrapper.class));
            verify(ticketCategoryMapper).reduceRemainNumber(2L, TICKET_CATEGORY_ID, PROGRAM_ID);
        }

        @Test
        void 库存不足_抛异常() {
            when(seatMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(seat(SEAT_ID_1, SellStatus.NO_SOLD.getCode()), seat(SEAT_ID_2, SellStatus.NO_SOLD.getCode())));
            when(ticketCategoryMapper.reduceRemainNumber(anyLong(), anyLong(), anyLong())).thenReturn(0);

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> programService.operateSeatLockAndTicketCategoryRemainNumber(reduceDto(SellStatus.LOCK.getCode())));
            assertEquals(BaseCode.UPDATE_TICKET_CATEGORY_COUNT_NOT_CORRECT.getCode(), ex.getCode());
        }
    }

    // ==================== operateProgramData ====================

    @Nested
    class OperateProgramData {

        @Test
        void 座位不存在_抛异常() {
            when(seatMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> programService.operateProgramData(operateDataDto(SellStatus.SOLD.getCode(), ProgramOrderVersion.V4_VERSION.getValue())));
            assertEquals(BaseCode.SEAT_NOT_EXIST.getCode(), ex.getCode());
        }

        @Test
        void 座位数量与预设不一致_抛异常() {
            when(seatMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(seat(SEAT_ID_1, SellStatus.LOCK.getCode())));

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> programService.operateProgramData(operateDataDto(SellStatus.SOLD.getCode(), ProgramOrderVersion.V4_VERSION.getValue())));
            assertEquals(BaseCode.SEAT_UPDATE_REL_COUNT_NOT_EQUAL_PRESET_COUNT.getCode(), ex.getCode());
        }

        @Test
        void 操作状态非法_抛异常() {
            when(seatMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(seat(SEAT_ID_1, SellStatus.LOCK.getCode()), seat(SEAT_ID_2, SellStatus.LOCK.getCode())));

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> programService.operateProgramData(operateDataDto(SellStatus.LOCK.getCode(), ProgramOrderVersion.V4_VERSION.getValue())));
            assertEquals(BaseCode.SEAT_OPERATE_IS_NOT_NOT_SOLD_OR_SOLD.getCode(), ex.getCode());
        }

        @Test
        void V1版本座位已售_抛异常() {
            when(seatMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(seat(SEAT_ID_1, SellStatus.SOLD.getCode()), seat(SEAT_ID_2, SellStatus.NO_SOLD.getCode())));

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> programService.operateProgramData(operateDataDto(SellStatus.SOLD.getCode(), ProgramOrderVersion.V1_VERSION.getValue())));
            assertEquals(BaseCode.SEAT_SOLD.getCode(), ex.getCode());
        }

        @Test
        void V1版本成功_置已售并扣库存() {
            when(seatMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(seat(SEAT_ID_1, SellStatus.NO_SOLD.getCode()), seat(SEAT_ID_2, SellStatus.NO_SOLD.getCode())));
            when(ticketCategoryMapper.batchUpdateRemainNumber(anyList(), anyLong())).thenReturn(1);

            Boolean result = programService.operateProgramData(operateDataDto(SellStatus.SOLD.getCode(), ProgramOrderVersion.V1_VERSION.getValue()));

            assertTrue(result);
            verify(seatMapper).update(any(Seat.class), any(LambdaUpdateWrapper.class));
            verify(ticketCategoryMapper).batchUpdateRemainNumber(anyList(), anyLong());
        }

        @Test
        void V1版本库存更新失败_抛异常() {
            when(seatMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(seat(SEAT_ID_1, SellStatus.NO_SOLD.getCode()), seat(SEAT_ID_2, SellStatus.NO_SOLD.getCode())));
            when(ticketCategoryMapper.batchUpdateRemainNumber(anyList(), anyLong())).thenReturn(0);

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> programService.operateProgramData(operateDataDto(SellStatus.SOLD.getCode(), ProgramOrderVersion.V1_VERSION.getValue())));
            assertEquals(BaseCode.UPDATE_TICKET_CATEGORY_COUNT_NOT_CORRECT.getCode(), ex.getCode());
        }

        @Test
        void V4版本座位非锁定_抛异常() {
            when(seatMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(seat(SEAT_ID_1, SellStatus.LOCK.getCode()), seat(SEAT_ID_2, SellStatus.NO_SOLD.getCode())));

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> programService.operateProgramData(operateDataDto(SellStatus.SOLD.getCode(), ProgramOrderVersion.V4_VERSION.getValue())));
            assertEquals(BaseCode.SEAT_IS_NOT_NOT_LOCK.getCode(), ex.getCode());
        }

        @Test
        void V4支付成功_锁定转已售不扣库存() {
            when(seatMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(seat(SEAT_ID_1, SellStatus.LOCK.getCode()), seat(SEAT_ID_2, SellStatus.LOCK.getCode())));

            Boolean result = programService.operateProgramData(operateDataDto(SellStatus.SOLD.getCode(), ProgramOrderVersion.V4_VERSION.getValue()));

            assertTrue(result);
            verify(seatMapper).update(any(Seat.class), any(LambdaUpdateWrapper.class));
            // 锁定阶段已扣库存，支付成功不再操作库存
            verify(ticketCategoryMapper, never()).batchUpdateRemainNumber(anyList(), anyLong());
            verify(ticketCategoryMapper, never()).increaseRemainNumber(anyLong(), anyLong(), anyLong());
        }

        @Test
        void V4取消_锁定转未售并归还库存() {
            when(seatMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(seat(SEAT_ID_1, SellStatus.LOCK.getCode()), seat(SEAT_ID_2, SellStatus.LOCK.getCode())));
            when(ticketCategoryMapper.increaseRemainNumber(anyLong(), anyLong(), anyLong())).thenReturn(1);

            Boolean result = programService.operateProgramData(operateDataDto(SellStatus.NO_SOLD.getCode(), ProgramOrderVersion.V4_VERSION.getValue()));

            assertTrue(result);
            verify(seatMapper).update(any(Seat.class), any(LambdaUpdateWrapper.class));
            verify(ticketCategoryMapper).increaseRemainNumber(2L, TICKET_CATEGORY_ID, PROGRAM_ID);
        }

        @Test
        void V4取消库存归还失败_抛异常() {
            when(seatMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(seat(SEAT_ID_1, SellStatus.LOCK.getCode()), seat(SEAT_ID_2, SellStatus.LOCK.getCode())));
            when(ticketCategoryMapper.increaseRemainNumber(anyLong(), anyLong(), anyLong())).thenReturn(0);

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> programService.operateProgramData(operateDataDto(SellStatus.NO_SOLD.getCode(), ProgramOrderVersion.V4_VERSION.getValue())));
            assertEquals(BaseCode.UPDATE_TICKET_CATEGORY_COUNT_NOT_CORRECT.getCode(), ex.getCode());
        }

        @Test
        void 订单版本为空_不抛NPE_按非V4处理() {
            when(seatMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(seat(SEAT_ID_1, SellStatus.NO_SOLD.getCode()), seat(SEAT_ID_2, SellStatus.NO_SOLD.getCode())));
            when(ticketCategoryMapper.batchUpdateRemainNumber(anyList(), anyLong())).thenReturn(1);

            Boolean result = programService.operateProgramData(operateDataDto(SellStatus.SOLD.getCode(), null));

            // orderVersion=null 不应 NPE，按 V1-V3（非 V4）语义处理
            assertTrue(result);
            verify(ticketCategoryMapper).batchUpdateRemainNumber(anyList(), anyLong());
        }
    }

    @Nested
    class ProgramQueryLayer {

        private ProgramMapper programMapper;
        private ProgramShowTimeMapper programShowTimeMapper;
        private ProgramCategoryMapper programCategoryMapper;
        private ProgramEs programEs;
        private BaseDataClient baseDataClient;
        private CompositeContainer compositeContainer;
        private UidGenerator uidGenerator;

        @BeforeEach
        void setUp() {
            programMapper = mock(ProgramMapper.class);
            programShowTimeMapper = mock(ProgramShowTimeMapper.class);
            programCategoryMapper = mock(ProgramCategoryMapper.class);
            programEs = mock(ProgramEs.class);
            baseDataClient = mock(BaseDataClient.class);
            compositeContainer = mock(CompositeContainer.class);
            uidGenerator = mock(UidGenerator.class);
            ReflectionTestUtils.setField(programService, "programMapper", programMapper);
            ReflectionTestUtils.setField(programService, "programShowTimeMapper", programShowTimeMapper);
            ReflectionTestUtils.setField(programService, "programCategoryMapper", programCategoryMapper);
            ReflectionTestUtils.setField(programService, "programEs", programEs);
            ReflectionTestUtils.setField(programService, "baseDataClient", baseDataClient);
            ReflectionTestUtils.setField(programService, "compositeContainer", compositeContainer);
            ReflectionTestUtils.setField(programService, "uidGenerator", uidGenerator);
        }

        private ProgramPageListDto pageListDto(Integer timeType) {
            ProgramPageListDto dto = new ProgramPageListDto();
            dto.setTimeType(timeType);
            dto.setPageNumber(1);
            dto.setPageSize(10);
            return dto;
        }

        @Test
        void setQueryTime_今日_起止为当天() {
            ProgramPageListDto dto = pageListDto(ProgramTimeType.TODAY);
            programService.setQueryTime(dto);
            assertEquals(dto.getStartDateTime(), dto.getEndDateTime());
            assertEquals(DateUtils.now(FORMAT_DATE), dto.getStartDateTime());
        }

        @Test
        void setQueryTime_明日_结束为明天() {
            ProgramPageListDto dto = pageListDto(ProgramTimeType.TOMORROW);
            programService.setQueryTime(dto);
            assertEquals(DateUtils.addDay(dto.getStartDateTime(), 1), dto.getEndDateTime());
        }

        @Test
        void setQueryTime_本周_结束为下周() {
            ProgramPageListDto dto = pageListDto(ProgramTimeType.WEEK);
            programService.setQueryTime(dto);
            assertEquals(DateUtils.addWeek(dto.getStartDateTime(), 1), dto.getEndDateTime());
        }

        @Test
        void setQueryTime_本月_结束为下月() {
            ProgramPageListDto dto = pageListDto(ProgramTimeType.MONTH);
            programService.setQueryTime(dto);
            assertEquals(DateUtils.addMonth(dto.getStartDateTime(), 1), dto.getEndDateTime());
        }

        @Test
        void setQueryTime_日历缺开始时间_抛异常() {
            ProgramPageListDto dto = pageListDto(ProgramTimeType.CALENDAR);
            dto.setEndDateTime(DateUtils.now(FORMAT_DATE));
            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> programService.setQueryTime(dto));
            assertEquals(BaseCode.START_DATE_TIME_NOT_EXIST.getCode(), ex.getCode());
        }

        @Test
        void setQueryTime_日历缺结束时间_抛异常() {
            ProgramPageListDto dto = pageListDto(ProgramTimeType.CALENDAR);
            dto.setStartDateTime(DateUtils.now(FORMAT_DATE));
            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> programService.setQueryTime(dto));
            assertEquals(BaseCode.END_DATE_TIME_NOT_EXIST.getCode(), ex.getCode());
        }

        @Test
        void setQueryTime_日历时间齐全_不抛并保留() {
            ProgramPageListDto dto = pageListDto(ProgramTimeType.CALENDAR);
            Date start = DateUtils.now(FORMAT_DATE);
            Date end = DateUtils.addDay(start, 1);
            dto.setStartDateTime(start);
            dto.setEndDateTime(end);
            programService.setQueryTime(dto);
            assertEquals(start, dto.getStartDateTime());
            assertEquals(end, dto.getEndDateTime());
        }

        @Test
        void setQueryTime_非法类型_清空时间() {
            ProgramPageListDto dto = pageListDto(999);
            dto.setStartDateTime(DateUtils.now(FORMAT_DATE));
            dto.setEndDateTime(DateUtils.now(FORMAT_DATE));
            programService.setQueryTime(dto);
            assertTrue(dto.getStartDateTime() == null && dto.getEndDateTime() == null);
        }

        @Test
        void add_生成id并插入() {
            when(uidGenerator.getUid()).thenReturn(888L);
            ProgramAddDto dto = new ProgramAddDto();
            dto.setTitle("测试演出");

            Long id = programService.add(dto);

            assertEquals(888L, id);
            ArgumentCaptor<Program> captor = ArgumentCaptor.forClass(Program.class);
            verify(programMapper).insert(captor.capture());
            assertEquals(888L, captor.getValue().getId());
            assertEquals("测试演出", captor.getValue().getTitle());
        }

        @Test
        void selectHomeList_ES有数据_直接返回不查DB() {
            ProgramHomeVo homeVo = new ProgramHomeVo();
            when(programEs.selectHomeList(any())).thenReturn(List.of(homeVo));

            List<ProgramHomeVo> result = programService.selectHomeList(new ProgramListDto());

            assertEquals(1, result.size());
            verify(programMapper, never()).selectHomeList(any());
        }

        @Test
        void selectHomeList_ES空且DB无数据_返回空() {
            when(programEs.selectHomeList(any())).thenReturn(null);
            when(programMapper.selectHomeList(any())).thenReturn(List.of());

            List<ProgramHomeVo> result = programService.selectHomeList(new ProgramListDto());

            assertTrue(result.isEmpty());
        }

        @Test
        void selectHomeList_ES空_走DB兜底组装() {
            Program program = new Program();
            program.setId(PROGRAM_ID);
            program.setParentProgramCategoryId(1L);
            when(programEs.selectHomeList(any())).thenReturn(null);
            when(programMapper.selectHomeList(any())).thenReturn(List.of(program));
            ProgramShowTime showTime = new ProgramShowTime();
            showTime.setProgramId(PROGRAM_ID);
            Date showDate = DateUtils.now(FORMAT_DATE);
            showTime.setShowTime(showDate);
            when(programShowTimeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(showTime));
            ProgramCategory category = new ProgramCategory();
            category.setId(1L);
            category.setName("演唱会");
            when(programCategoryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(category));
            TicketCategoryAggregate aggregate = new TicketCategoryAggregate();
            aggregate.setProgramId(PROGRAM_ID);
            aggregate.setMinPrice(new BigDecimal("100"));
            aggregate.setMaxPrice(new BigDecimal("500"));
            when(ticketCategoryMapper.selectAggregateList(anyList())).thenReturn(List.of(aggregate));

            List<ProgramHomeVo> result = programService.selectHomeList(new ProgramListDto());

            assertEquals(1, result.size());
            ProgramHomeVo homeVo = result.get(0);
            assertEquals("演唱会", homeVo.getCategoryName());
            assertEquals(1L, homeVo.getCategoryId());
            ProgramListVo listVo = homeVo.getProgramListVoList().get(0);
            assertEquals(showDate, listVo.getShowTime());
            assertEquals(new BigDecimal("100"), listVo.getMinPrice());
            assertEquals(new BigDecimal("500"), listVo.getMaxPrice());
        }

        @Test
        void selectPage_ES有数据_直接返回() {
            PageVo<ProgramListVo> pageVo = new PageVo<>(1L, 10L, 1L, List.of(new ProgramListVo()));
            when(programEs.selectPage(any())).thenReturn(pageVo);

            PageVo<ProgramListVo> result = programService.selectPage(pageListDto(ProgramTimeType.ALL));

            assertEquals(1, result.getList().size());
            verify(programMapper, never()).selectPage(any(IPage.class), any(ProgramPageListDto.class));
        }

        @Test
        void selectPage_ES空_走DB分页组装() {
            when(programEs.selectPage(any())).thenReturn(null);
            ProgramJoinShowTime joinShowTime = new ProgramJoinShowTime();
            joinShowTime.setId(PROGRAM_ID);
            joinShowTime.setProgramCategoryId(1L);
            joinShowTime.setAreaId(2L);
            IPage<ProgramJoinShowTime> iPage = mock(IPage.class);
            when(iPage.getRecords()).thenReturn(List.of(joinShowTime));
            when(iPage.getCurrent()).thenReturn(1L);
            when(iPage.getSize()).thenReturn(10L);
            when(iPage.getTotal()).thenReturn(1L);
            when(programMapper.selectPage(any(IPage.class), any(ProgramPageListDto.class))).thenReturn(iPage);
            ProgramCategory category = new ProgramCategory();
            category.setId(1L);
            category.setName("演唱会");
            when(programCategoryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(category));
            TicketCategoryAggregate aggregate = new TicketCategoryAggregate();
            aggregate.setProgramId(PROGRAM_ID);
            aggregate.setMinPrice(new BigDecimal("100"));
            aggregate.setMaxPrice(new BigDecimal("500"));
            when(ticketCategoryMapper.selectAggregateList(anyList())).thenReturn(List.of(aggregate));
            ApiResponse<List<AreaVo>> areaResponse = mock(ApiResponse.class);
            when(areaResponse.getCode()).thenReturn(ApiResponse.ok().getCode());
            AreaVo areaVo = new AreaVo();
            areaVo.setId(2L);
            areaVo.setName("北京");
            when(areaResponse.getData()).thenReturn(List.of(areaVo));
            when(baseDataClient.selectByIdList(any())).thenReturn(areaResponse);

            PageVo<ProgramListVo> result = programService.selectPage(pageListDto(ProgramTimeType.ALL));

            assertEquals(1L, result.getTotalSize());
            ProgramListVo listVo = result.getList().get(0);
            assertEquals("北京", listVo.getAreaName());
            assertEquals("演唱会", listVo.getProgramCategoryName());
            assertEquals(new BigDecimal("100"), listVo.getMinPrice());
            assertEquals(new BigDecimal("500"), listVo.getMaxPrice());
            verify(baseDataClient).selectByIdList(any());
        }

        @Test
        void recommendList_ES有数据_直接返回并执行校验() {
            when(programEs.recommendList(any())).thenReturn(List.of(new ProgramListVo()));

            List<ProgramListVo> result = programService.recommendList(new ProgramRecommendListDto());

            assertEquals(1, result.size());
            verify(compositeContainer).execute(any(), any());
            verify(programMapper, never()).selectPage(any(IPage.class), any(ProgramPageListDto.class));
        }

        @Test
        void recommendList_ES空_走DB兜底按热度取10条() {
            when(programEs.recommendList(any())).thenReturn(null);
            IPage<ProgramJoinShowTime> iPage = mock(IPage.class);
            when(iPage.getRecords()).thenReturn(List.of());
            when(programMapper.selectPage(any(IPage.class), any(ProgramPageListDto.class))).thenReturn(iPage);

            List<ProgramListVo> result = programService.recommendList(new ProgramRecommendListDto());

            assertTrue(result.isEmpty());
            verify(programMapper).selectPage(any(IPage.class), argThat((ProgramPageListDto dto) ->
                    dto.getType() != null && dto.getType() == 2
                            && dto.getPageNumber() != null && dto.getPageNumber() == 1
                            && dto.getPageSize() != null && dto.getPageSize() == 10));
        }
    }
}
