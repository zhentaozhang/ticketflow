package com.ticketflow.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baidu.fsg.uid.UidGenerator;
import com.ticketflow.BusinessThreadPool;
import com.ticketflow.client.OrderClient;
import com.ticketflow.mq.callback.FailureCallback;
import com.ticketflow.mq.callback.SuccessCallback;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.domain.OrderCreateMq;
import com.ticketflow.domain.PendingOrder;
import com.ticketflow.domain.PurchaseSeat;
import com.ticketflow.dto.DelayOrderCancelDto;
import com.ticketflow.dto.OrderCreateDto;
import com.ticketflow.dto.OrderTicketUserCreateDto;
import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.dto.SeatDto;
import com.ticketflow.entity.ProgramRecordTask;
import com.ticketflow.entity.ProgramShowTime;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.OrderStatus;
import com.ticketflow.enums.ProgramOrderVersion;
import com.ticketflow.enums.SellStatus;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.mapper.ProgramRecordTaskMapper;
import com.ticketflow.service.delaysend.DelayOrderCancelSend;
import com.ticketflow.service.domain.CreateOrderTemporaryData;
import com.ticketflow.service.kafka.CreateOrderSend;
import com.ticketflow.service.lua.ProgramCacheCreateOrderData;
import com.ticketflow.service.lua.ProgramCacheCreateOrderResolutionOperate;
import com.ticketflow.service.lua.ProgramCacheCreateOrderV5ResolutionOperate;
import com.ticketflow.service.lua.ProgramCacheResolutionOperate;
import com.ticketflow.service.stock.ProgramLocalStockGate;
import com.ticketflow.core.SpringUtil;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.vo.ProgramVo;
import com.ticketflow.vo.SeatVo;
import com.ticketflow.vo.TicketCategoryVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;


import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProgramOrderServiceTest {

    @Spy
    @InjectMocks
    private ProgramOrderService programOrderService;

    @BeforeEach
    void setUp() {
        mockSpringUtil();
        mockSeatCacheExistsCheck(redisCache);
    }

    private static void mockSpringUtil() {
        ConfigurableApplicationContext mockContext = mock(ConfigurableApplicationContext.class);
        ConfigurableEnvironment mockEnv = mock(ConfigurableEnvironment.class);
        lenient().when(mockContext.getEnvironment()).thenReturn(mockEnv);
        lenient().when(mockEnv.getProperty(eq("prefix.distinction.name"), anyString())).thenReturn("test");
        ReflectionTestUtils.setField(SpringUtil.class, "configurableApplicationContext", mockContext);
    }

    /**
     * mock 座位三区缓存存在性检查的 Lua 执行（hasSeatResolutionCache 走 getInstance().execute）。
     * 默认返回 0（缓存未预热），保持与旧 hasKey 默认 false 一致：走 selectSeatResolution 预热分支。
     */
    private static void mockSeatCacheExistsCheck(com.ticketflow.redis.RedisCache redisCache) {
        RedisTemplate redisTemplate = mock(RedisTemplate.class);
        lenient().when(redisCache.getInstance()).thenReturn(redisTemplate);
        lenient().when(redisTemplate.execute(any(RedisScript.class), anyList())).thenReturn(0L);
    }

    @Mock private OrderClient orderClient;
    @Mock private UidGenerator uidGenerator;
    @Mock private ProgramCacheResolutionOperate programCacheResolutionOperate;
    @Mock private ProgramCacheCreateOrderResolutionOperate programCacheCreateOrderResolutionOperate;
    @Mock private ProgramCacheCreateOrderV5ResolutionOperate programCacheCreateOrderV5ResolutionOperate;
    @Mock private ProgramLocalStockGate programLocalStockGate;
    @Mock private DelayOrderCancelSend delayOrderCancelSend;
    @Mock private CreateOrderSend createOrderSend;
    @Mock private ProgramService programService;
    @Mock private ProgramShowTimeService programShowTimeService;
    @Mock private TicketCategoryService ticketCategoryService;
    @Mock private SeatService seatService;
    @Mock private ProgramRecordTaskMapper programRecordTaskMapper;
    @Mock private com.ticketflow.redis.RedisCache redisCache;

    private static final Long PROGRAM_ID = 501L;
    private static final Long USER_ID = 1001L;
    private static final Long TICKET_CATEGORY_ID = 5L;

    private ProgramShowTime createShowTime() {
        ProgramShowTime showTime = new ProgramShowTime();
        showTime.setProgramId(PROGRAM_ID);
        showTime.setShowTime(new Date(System.currentTimeMillis() + 86400000L));
        showTime.setShowDayTime(new Date());
        showTime.setShowWeekTime("周五");
        return showTime;
    }

    private TicketCategoryVo createTicketCategory(Long id) {
        TicketCategoryVo vo = new TicketCategoryVo();
        vo.setId(id);
        vo.setIntroduce("A区");
        vo.setPrice(new BigDecimal("100"));
        return vo;
    }

    private SeatVo createSeatVo(Long id, int row, int col, Long categoryId) {
        SeatVo seatVo = new SeatVo();
        seatVo.setId(id);
        seatVo.setProgramId(PROGRAM_ID);
        seatVo.setTicketCategoryId(categoryId);
        seatVo.setRowCode(row);
        seatVo.setColCode(col);
        seatVo.setPrice(new BigDecimal("100"));
        seatVo.setSellStatus(SellStatus.NO_SOLD.getCode());
        return seatVo;
    }

    private PurchaseSeat createPurchaseSeat(Long id, Long ticketUserId, Long categoryId) {
        PurchaseSeat ps = new PurchaseSeat();
        ps.setId(id);
        ps.setProgramId(PROGRAM_ID);
        ps.setTicketCategoryId(categoryId);
        ps.setTicketUserId(ticketUserId);
        ps.setRowCode(1);
        ps.setColCode(id.intValue());
        ps.setPrice(new BigDecimal("100"));
        ps.setSellStatus(SellStatus.NO_SOLD.getCode());
        return ps;
    }

    private ProgramVo createProgramVo() {
        ProgramVo vo = new ProgramVo();
        vo.setId(PROGRAM_ID);
        vo.setTitle("颜人中「MOMENT\u207f」演唱会-北京站");
        vo.setPlace("华熙LIVE");
        vo.setItemPicture("https://picsum.photos/seed/yanrenzhong/800/400");
        vo.setShowTime(new Date());
        vo.setPermitChooseSeat(1);
        return vo;
    }

    private ProgramOrderCreateDto createDtoWithSeats(int seatCount) {
        ProgramOrderCreateDto dto = new ProgramOrderCreateDto();
        dto.setProgramId(PROGRAM_ID);
        dto.setUserId(USER_ID);
        dto.setTicketUserIdList(new ArrayList<>());
        List<SeatDto> seatDtos = new ArrayList<>();
        for (int i = 0; i < seatCount; i++) {
            SeatDto seatDto = new SeatDto();
            seatDto.setId(50L + i);
            seatDto.setTicketCategoryId(TICKET_CATEGORY_ID);
            seatDto.setRowCode(1);
            seatDto.setColCode(i + 1);
            seatDto.setPrice(new BigDecimal("100"));
            seatDtos.add(seatDto);
            dto.getTicketUserIdList().add(2000L + i);
        }
        dto.setSeatDtoList(seatDtos);
        return dto;
    }

    // ==================== P0: getTicketCategoryList ====================

    @Nested
    class GetTicketCategoryList {

        @Test
        void withSeatSelection_Success() {
            ProgramOrderCreateDto dto = createDtoWithSeats(1);
            when(ticketCategoryService.selectTicketCategoryListByProgramIdMultipleCache(eq(PROGRAM_ID), any()))
                    .thenReturn(List.of(createTicketCategory(TICKET_CATEGORY_ID)));

            List<TicketCategoryVo> result = programOrderService.getTicketCategoryList(dto, new Date());

            assertEquals(1, result.size());
            assertEquals(TICKET_CATEGORY_ID, result.get(0).getId());
        }

        @Test
        void withoutSeatSelection_Success() {
            ProgramOrderCreateDto dto = new ProgramOrderCreateDto();
            dto.setProgramId(PROGRAM_ID);
            dto.setTicketCategoryId(TICKET_CATEGORY_ID);
            when(ticketCategoryService.selectTicketCategoryListByProgramIdMultipleCache(eq(PROGRAM_ID), any()))
                    .thenReturn(List.of(createTicketCategory(TICKET_CATEGORY_ID)));

            List<TicketCategoryVo> result = programOrderService.getTicketCategoryList(dto, new Date());

            assertEquals(1, result.size());
            assertEquals(TICKET_CATEGORY_ID, result.get(0).getId());
        }

        @Test
        void whenSeatTicketCategoryNotFound_ThrowsException() {
            ProgramOrderCreateDto dto = createDtoWithSeats(1);
            dto.getSeatDtoList().get(0).setTicketCategoryId(999L);
            when(ticketCategoryService.selectTicketCategoryListByProgramIdMultipleCache(eq(PROGRAM_ID), any()))
                    .thenReturn(List.of(createTicketCategory(TICKET_CATEGORY_ID)));

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> programOrderService.getTicketCategoryList(dto, new Date()));
            assertEquals(BaseCode.TICKET_CATEGORY_NOT_EXIST_V2.getCode(), ex.getCode());
        }

        @Test
        void whenCategoryNotFound_ThrowsException() {
            ProgramOrderCreateDto dto = new ProgramOrderCreateDto();
            dto.setProgramId(PROGRAM_ID);
            dto.setTicketCategoryId(999L);
            when(ticketCategoryService.selectTicketCategoryListByProgramIdMultipleCache(eq(PROGRAM_ID), any()))
                    .thenReturn(List.of(createTicketCategory(TICKET_CATEGORY_ID)));

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> programOrderService.getTicketCategoryList(dto, new Date()));
            assertEquals(BaseCode.TICKET_CATEGORY_NOT_EXIST_V2.getCode(), ex.getCode());
        }
    }

    // ==================== P0: createOrderOperateProgramCacheResolution ====================

    @Nested
    class CreateOrderOperateProgramCacheResolution {

        @Test
        void withSeatSelection_Success() {
            ProgramOrderCreateDto dto = createDtoWithSeats(2);

            when(programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(PROGRAM_ID))
                    .thenReturn(createShowTime());
            when(ticketCategoryService.selectTicketCategoryListByProgramIdMultipleCache(eq(PROGRAM_ID), any()))
                    .thenReturn(List.of(createTicketCategory(TICKET_CATEGORY_ID)));
            when(seatService.selectSeatResolution(anyLong(), anyLong(), anyLong(), any()))
                    .thenReturn(List.of(createSeatVo(50L, 1, 1, TICKET_CATEGORY_ID)));
            Map<String, Long> remainMap = new HashMap<>();
            remainMap.put(String.valueOf(TICKET_CATEGORY_ID), 100L);
            when(ticketCategoryService.getRedisRemainNumberResolution(PROGRAM_ID, TICKET_CATEGORY_ID))
                    .thenReturn(remainMap);
            when(uidGenerator.getUid()).thenReturn(888L);

            List<PurchaseSeat> purchaseSeats = Arrays.asList(
                    createPurchaseSeat(50L, 2000L, TICKET_CATEGORY_ID),
                    createPurchaseSeat(51L, 2001L, TICKET_CATEGORY_ID));
            ProgramCacheCreateOrderData luaResult = new ProgramCacheCreateOrderData();
            luaResult.setCode(BaseCode.SUCCESS.getCode());
            luaResult.setPurchaseSeatList(purchaseSeats);
            when(programCacheCreateOrderResolutionOperate.programCacheOperate(anyList(), any()))
                    .thenReturn(luaResult);

            CreateOrderTemporaryData result =
                    programOrderService.createOrderOperateProgramCacheResolution(dto);

            assertNotNull(result);
            assertEquals(888L, result.getIdentifierId().longValue());
            assertEquals(2, result.getPurchaseSeatList().size());
        }

        @Test
        void withoutSeatSelection_Success() {
            ProgramOrderCreateDto dto = new ProgramOrderCreateDto();
            dto.setProgramId(PROGRAM_ID);
            dto.setUserId(USER_ID);
            dto.setTicketCategoryId(TICKET_CATEGORY_ID);
            dto.setTicketCount(2);
            dto.setTicketUserIdList(List.of(2000L, 2001L));

            when(programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(PROGRAM_ID))
                    .thenReturn(createShowTime());
            when(ticketCategoryService.selectTicketCategoryListByProgramIdMultipleCache(eq(PROGRAM_ID), any()))
                    .thenReturn(List.of(createTicketCategory(TICKET_CATEGORY_ID)));
            when(seatService.selectSeatResolution(anyLong(), anyLong(), anyLong(), any()))
                    .thenReturn(new ArrayList<>());
            Map<String, Long> remainMap = new HashMap<>();
            remainMap.put(String.valueOf(TICKET_CATEGORY_ID), 100L);
            when(ticketCategoryService.getRedisRemainNumberResolution(PROGRAM_ID, TICKET_CATEGORY_ID))
                    .thenReturn(remainMap);
            when(uidGenerator.getUid()).thenReturn(888L);

            // 应用层匹配：no_sold hash 中提供相邻座位
            Map<String, SeatVo> noSoldSeatMap = new HashMap<>();
            noSoldSeatMap.put("50", createSeatVo(50L, 1, 1, TICKET_CATEGORY_ID));
            noSoldSeatMap.put("51", createSeatVo(51L, 1, 2, TICKET_CATEGORY_ID));
            noSoldSeatMap.put("52", createSeatVo(52L, 1, 5, TICKET_CATEGORY_ID));
            when(redisCache.getAllMapForHash(any(), eq(SeatVo.class))).thenReturn(noSoldSeatMap);

            ProgramCacheCreateOrderData luaResult = new ProgramCacheCreateOrderData();
            luaResult.setCode(BaseCode.SUCCESS.getCode());
            luaResult.setPurchaseSeatList(List.of(
                    createPurchaseSeat(50L, 2000L, TICKET_CATEGORY_ID),
                    createPurchaseSeat(51L, 2001L, TICKET_CATEGORY_ID)));
            when(programCacheCreateOrderResolutionOperate.programCacheOperate(anyList(), any()))
                    .thenReturn(luaResult);

            CreateOrderTemporaryData result =
                    programOrderService.createOrderOperateProgramCacheResolution(dto);

            assertNotNull(result);
            assertEquals(2, result.getPurchaseSeatList().size());
            ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<String[]> dataCaptor = ArgumentCaptor.forClass(String[].class);
            verify(programCacheCreateOrderResolutionOperate).programCacheOperate(keysCaptor.capture(), dataCaptor.capture());
            // 不选座已改为应用层匹配，Lua 只负责校验+锁定：keys 首位标识为 "1"
            assertEquals("1", keysCaptor.getValue().get(0));
            // Lua 参数携带应用层匹配出的相邻座位（50,51），而非在 Redis 内全量匹配
            JSONArray seatDataArray = JSON.parseArray(String.valueOf(dataCaptor.getValue()[1]));
            JSONArray matchedSeatArray = JSON.parseArray(seatDataArray.getJSONObject(0).getString("seatDataList"));
            List<Long> matchedSeatIds = matchedSeatArray.stream().map(o -> ((JSONObject) o).getLong("id")).toList();
            assertEquals(List.of(50L, 51L), matchedSeatIds);
        }

        @Test
        void withoutSeatSelection_WhenNoAdjacentSeats_ThrowsSeatOccupy() {
            ProgramOrderCreateDto dto = new ProgramOrderCreateDto();
            dto.setProgramId(PROGRAM_ID);
            dto.setUserId(USER_ID);
            dto.setTicketCategoryId(TICKET_CATEGORY_ID);
            dto.setTicketCount(2);
            dto.setTicketUserIdList(List.of(2000L, 2001L));

            when(programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(PROGRAM_ID))
                    .thenReturn(createShowTime());
            when(ticketCategoryService.selectTicketCategoryListByProgramIdMultipleCache(eq(PROGRAM_ID), any()))
                    .thenReturn(List.of(createTicketCategory(TICKET_CATEGORY_ID)));
            when(seatService.selectSeatResolution(anyLong(), anyLong(), anyLong(), any()))
                    .thenReturn(new ArrayList<>());
            Map<String, Long> remainMap = new HashMap<>();
            remainMap.put(String.valueOf(TICKET_CATEGORY_ID), 100L);
            when(ticketCategoryService.getRedisRemainNumberResolution(PROGRAM_ID, TICKET_CATEGORY_ID))
                    .thenReturn(remainMap);
            when(uidGenerator.getUid()).thenReturn(888L);

            // 座位不连续（列号 1 和 3），匹配不到相邻的 2 个座位
            Map<String, SeatVo> noSoldSeatMap = new HashMap<>();
            noSoldSeatMap.put("50", createSeatVo(50L, 1, 1, TICKET_CATEGORY_ID));
            noSoldSeatMap.put("52", createSeatVo(52L, 1, 3, TICKET_CATEGORY_ID));
            when(redisCache.getAllMapForHash(any(), eq(SeatVo.class))).thenReturn(noSoldSeatMap);

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> programOrderService.createOrderOperateProgramCacheResolution(dto));
            assertEquals(BaseCode.SEAT_OCCUPY.getCode(), ex.getCode());
            verify(programCacheCreateOrderResolutionOperate, never()).programCacheOperate(anyList(), any());
        }

        @Test
        void withoutSeatSelection_WhenSeatLockedByConcurrentOrder_RetriesAndSucceeds() {
            ProgramOrderCreateDto dto = new ProgramOrderCreateDto();
            dto.setProgramId(PROGRAM_ID);
            dto.setUserId(USER_ID);
            dto.setTicketCategoryId(TICKET_CATEGORY_ID);
            dto.setTicketCount(2);
            dto.setTicketUserIdList(List.of(2000L, 2001L));

            when(programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(PROGRAM_ID))
                    .thenReturn(createShowTime());
            when(ticketCategoryService.selectTicketCategoryListByProgramIdMultipleCache(eq(PROGRAM_ID), any()))
                    .thenReturn(List.of(createTicketCategory(TICKET_CATEGORY_ID)));
            when(seatService.selectSeatResolution(anyLong(), anyLong(), anyLong(), any()))
                    .thenReturn(new ArrayList<>());
            Map<String, Long> remainMap = new HashMap<>();
            remainMap.put(String.valueOf(TICKET_CATEGORY_ID), 100L);
            when(ticketCategoryService.getRedisRemainNumberResolution(PROGRAM_ID, TICKET_CATEGORY_ID))
                    .thenReturn(remainMap);
            when(uidGenerator.getUid()).thenReturn(888L);

            // 第一次 Lua 返回 40002（并发抢占），重试后成功
            when(redisCache.getAllMapForHash(any(), eq(SeatVo.class)))
                    .thenReturn(adjacentSeatMap(50L, 51L, 52L))
                    .thenReturn(adjacentSeatMap(50L, 51L, 52L));
            ProgramCacheCreateOrderData lockedResult = new ProgramCacheCreateOrderData();
            lockedResult.setCode(BaseCode.SEAT_LOCK.getCode());
            ProgramCacheCreateOrderData successResult = new ProgramCacheCreateOrderData();
            successResult.setCode(BaseCode.SUCCESS.getCode());
            successResult.setPurchaseSeatList(List.of(
                    createPurchaseSeat(50L, 2000L, TICKET_CATEGORY_ID),
                    createPurchaseSeat(51L, 2001L, TICKET_CATEGORY_ID)));
            when(programCacheCreateOrderResolutionOperate.programCacheOperate(anyList(), any()))
                    .thenReturn(lockedResult)
                    .thenReturn(successResult);

            CreateOrderTemporaryData result =
                    programOrderService.createOrderOperateProgramCacheResolution(dto);

            assertNotNull(result);
            assertEquals(2, result.getPurchaseSeatList().size());
            verify(programCacheCreateOrderResolutionOperate, times(2)).programCacheOperate(anyList(), any());
            verify(redisCache, times(2)).getAllMapForHash(any(), eq(SeatVo.class));
        }

        @Test
        void withoutSeatSelection_WhenRetryExhausted_ThrowsLastError() {
            ProgramOrderCreateDto dto = new ProgramOrderCreateDto();
            dto.setProgramId(PROGRAM_ID);
            dto.setUserId(USER_ID);
            dto.setTicketCategoryId(TICKET_CATEGORY_ID);
            dto.setTicketCount(2);
            dto.setTicketUserIdList(List.of(2000L, 2001L));

            when(programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(PROGRAM_ID))
                    .thenReturn(createShowTime());
            when(ticketCategoryService.selectTicketCategoryListByProgramIdMultipleCache(eq(PROGRAM_ID), any()))
                    .thenReturn(List.of(createTicketCategory(TICKET_CATEGORY_ID)));
            when(seatService.selectSeatResolution(anyLong(), anyLong(), anyLong(), any()))
                    .thenReturn(new ArrayList<>());
            Map<String, Long> remainMap = new HashMap<>();
            remainMap.put(String.valueOf(TICKET_CATEGORY_ID), 100L);
            when(ticketCategoryService.getRedisRemainNumberResolution(PROGRAM_ID, TICKET_CATEGORY_ID))
                    .thenReturn(remainMap);
            when(uidGenerator.getUid()).thenReturn(888L);

            when(redisCache.getAllMapForHash(any(), eq(SeatVo.class))).thenReturn(adjacentSeatMap(50L, 51L));
            ProgramCacheCreateOrderData lockedResult = new ProgramCacheCreateOrderData();
            lockedResult.setCode(BaseCode.SEAT_LOCK.getCode());
            when(programCacheCreateOrderResolutionOperate.programCacheOperate(anyList(), any()))
                    .thenReturn(lockedResult);

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> programOrderService.createOrderOperateProgramCacheResolution(dto));
            assertEquals(BaseCode.SEAT_LOCK.getCode(), ex.getCode());
        }

        private Map<String, SeatVo> adjacentSeatMap(Long... seatIds) {
            Map<String, SeatVo> noSoldSeatMap = new HashMap<>();
            int col = 1;
            for (Long seatId : seatIds) {
                noSoldSeatMap.put(String.valueOf(seatId), createSeatVo(seatId, 1, col++, TICKET_CATEGORY_ID));
            }
            return noSoldSeatMap;
        }

        @Test
        void whenLuaFails_ThrowsException() {
            ProgramOrderCreateDto dto = createDtoWithSeats(1);

            when(programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(PROGRAM_ID))
                    .thenReturn(createShowTime());
            when(ticketCategoryService.selectTicketCategoryListByProgramIdMultipleCache(eq(PROGRAM_ID), any()))
                    .thenReturn(List.of(createTicketCategory(TICKET_CATEGORY_ID)));
            when(seatService.selectSeatResolution(anyLong(), anyLong(), anyLong(), any()))
                    .thenReturn(new ArrayList<>());
            Map<String, Long> remainMap = new HashMap<>();
            remainMap.put(String.valueOf(TICKET_CATEGORY_ID), 100L);
            when(ticketCategoryService.getRedisRemainNumberResolution(PROGRAM_ID, TICKET_CATEGORY_ID))
                    .thenReturn(remainMap);
            when(uidGenerator.getUid()).thenReturn(888L);

            ProgramCacheCreateOrderData luaResult = new ProgramCacheCreateOrderData();
            luaResult.setCode(BaseCode.SEAT_LOCK.getCode());
            when(programCacheCreateOrderResolutionOperate.programCacheOperate(anyList(), any()))
                    .thenReturn(luaResult);

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> programOrderService.createOrderOperateProgramCacheResolution(dto));
            assertEquals(BaseCode.SEAT_LOCK.getCode(), ex.getCode());
        }
    }

    // ==================== P0: matchAdjacentSeats 应用层相邻座位匹配 ====================

    @Nested
    class MatchAdjacentSeats {

        @Test
        void sameRowConsecutiveColumns_ReturnsMatchedSeats() {
            List<SeatVo> seats = List.of(
                    createSeatVo(1L, 1, 1, TICKET_CATEGORY_ID),
                    createSeatVo(2L, 1, 2, TICKET_CATEGORY_ID),
                    createSeatVo(3L, 1, 3, TICKET_CATEGORY_ID));
            List<SeatVo> matched = programOrderService.matchAdjacentSeats(seats, 2);
            assertEquals(2, matched.size());
            assertEquals(1L, matched.get(0).getId().longValue());
            assertEquals(2L, matched.get(1).getId().longValue());
        }

        @Test
        void unsortedInput_StillMatchesAfterSorting() {
            List<SeatVo> seats = List.of(
                    createSeatVo(3L, 1, 3, TICKET_CATEGORY_ID),
                    createSeatVo(1L, 1, 1, TICKET_CATEGORY_ID),
                    createSeatVo(2L, 1, 2, TICKET_CATEGORY_ID));
            List<SeatVo> matched = programOrderService.matchAdjacentSeats(seats, 2);
            assertEquals(2, matched.size());
            assertEquals(1L, matched.get(0).getId().longValue());
        }

        @Test
        void gapInColumns_SkipsToNextAdjacentWindow() {
            List<SeatVo> seats = List.of(
                    createSeatVo(1L, 1, 1, TICKET_CATEGORY_ID),
                    createSeatVo(2L, 1, 2, TICKET_CATEGORY_ID),
                    createSeatVo(4L, 1, 5, TICKET_CATEGORY_ID),
                    createSeatVo(5L, 1, 6, TICKET_CATEGORY_ID));
            List<SeatVo> matched = programOrderService.matchAdjacentSeats(seats, 2);
            assertEquals(2, matched.size());
            assertEquals(1L, matched.get(0).getId().longValue());
            assertEquals(2L, matched.get(1).getId().longValue());
        }

        @Test
        void differentRows_NeverMatches() {
            List<SeatVo> seats = List.of(
                    createSeatVo(1L, 1, 1, TICKET_CATEGORY_ID),
                    createSeatVo(2L, 2, 2, TICKET_CATEGORY_ID));
            List<SeatVo> matched = programOrderService.matchAdjacentSeats(seats, 2);
            assertTrue(matched.isEmpty());
        }

        @Test
        void insufficientSeats_ReturnsEmpty() {
            List<SeatVo> seats = List.of(
                    createSeatVo(1L, 1, 1, TICKET_CATEGORY_ID),
                    createSeatVo(2L, 1, 2, TICKET_CATEGORY_ID));
            List<SeatVo> matched = programOrderService.matchAdjacentSeats(seats, 3);
            assertTrue(matched.isEmpty());
        }

        @Test
        void emptyInput_ReturnsEmpty() {
            assertTrue(programOrderService.matchAdjacentSeats(new ArrayList<>(), 2).isEmpty());
        }
    }

    // ==================== P1: createNew (V3) ====================

    @Nested
    class CreateNew {

        @Test
        void success_WithSeatSelection() {
            ProgramOrderCreateDto dto = createDtoWithSeats(2);

            when(programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(PROGRAM_ID))
                    .thenReturn(createShowTime());
            when(ticketCategoryService.selectTicketCategoryListByProgramIdMultipleCache(eq(PROGRAM_ID), any()))
                    .thenReturn(List.of(createTicketCategory(TICKET_CATEGORY_ID)));
            when(seatService.selectSeatResolution(anyLong(), anyLong(), anyLong(), any()))
                    .thenReturn(List.of(createSeatVo(50L, 1, 1, TICKET_CATEGORY_ID)));
            Map<String, Long> remainMap = new HashMap<>();
            remainMap.put(String.valueOf(TICKET_CATEGORY_ID), 100L);
            when(ticketCategoryService.getRedisRemainNumberResolution(PROGRAM_ID, TICKET_CATEGORY_ID))
                    .thenReturn(remainMap);
            when(uidGenerator.getUid()).thenReturn(888L, 12345L);

            List<PurchaseSeat> purchaseSeats = Arrays.asList(
                    createPurchaseSeat(50L, 2000L, TICKET_CATEGORY_ID),
                    createPurchaseSeat(51L, 2001L, TICKET_CATEGORY_ID));
            ProgramCacheCreateOrderData luaResult = new ProgramCacheCreateOrderData();
            luaResult.setCode(BaseCode.SUCCESS.getCode());
            luaResult.setPurchaseSeatList(purchaseSeats);
            when(programCacheCreateOrderResolutionOperate.programCacheOperate(anyList(), any()))
                    .thenReturn(luaResult);

            when(programService.simpleGetProgramAndShowMultipleCache(PROGRAM_ID))
                    .thenReturn(createProgramVo());
            when(orderClient.create(any(OrderCreateDto.class)))
                    .thenReturn(ApiResponse.ok("2024001"));

            String result = programOrderService.createNew(dto, ProgramOrderVersion.V3_VERSION.getValue());

            assertNotNull(result);
            assertEquals("2024001", result);
            verify(delayOrderCancelSend).sendMessage(any(DelayOrderCancelDto.class));
        }
    }

    // ==================== P1: createNewAsync (V4) ====================

    @Nested
    class CreateNewAsync {

        @Test
        void success_SendsKafkaAndReturnsOrderNumber() {
            ProgramOrderCreateDto dto = createDtoWithSeats(1);

            when(programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(PROGRAM_ID))
                    .thenReturn(createShowTime());
            when(ticketCategoryService.selectTicketCategoryListByProgramIdMultipleCache(eq(PROGRAM_ID), any()))
                    .thenReturn(List.of(createTicketCategory(TICKET_CATEGORY_ID)));
            when(seatService.selectSeatResolution(anyLong(), anyLong(), anyLong(), any()))
                    .thenReturn(List.of(createSeatVo(50L, 1, 1, TICKET_CATEGORY_ID)));
            Map<String, Long> remainMap = new HashMap<>();
            remainMap.put(String.valueOf(TICKET_CATEGORY_ID), 100L);
            when(ticketCategoryService.getRedisRemainNumberResolution(PROGRAM_ID, TICKET_CATEGORY_ID))
                    .thenReturn(remainMap);
            when(uidGenerator.getUid()).thenReturn(888L, 12345L);

            List<PurchaseSeat> purchaseSeats = List.of(createPurchaseSeat(50L, 2000L, TICKET_CATEGORY_ID));
            ProgramCacheCreateOrderData luaResult = new ProgramCacheCreateOrderData();
            luaResult.setCode(BaseCode.SUCCESS.getCode());
            luaResult.setPurchaseSeatList(purchaseSeats);
            when(programCacheCreateOrderResolutionOperate.programCacheOperate(anyList(), any()))
                    .thenReturn(luaResult);

            when(programService.simpleGetProgramAndShowMultipleCache(PROGRAM_ID))
                    .thenReturn(createProgramVo());

            RecordMetadata mockMetadata = mock(RecordMetadata.class);
            SendResult mockSendResult = mock(SendResult.class);
            when(mockSendResult.getRecordMetadata()).thenReturn(mockMetadata);
            doAnswer(invocation -> {
                SuccessCallback callback = invocation.getArgument(1);
                if (callback != null) {
                    callback.onSuccess(mockSendResult);
                }
                return null;
            }).when(createOrderSend).sendMessage(anyString(), any(), any());

            String result = programOrderService.createNewAsync(dto, ProgramOrderVersion.V4_VERSION.getValue());

            assertNotNull(result);
            verify(createOrderSend).sendMessage(anyString(), any(), any());
            verify(delayOrderCancelSend).sendMessage(any(DelayOrderCancelDto.class));
        }

        @Test
        void whenKafkaSendFails_RollsBackCacheAndThrows() {
            ProgramOrderCreateDto dto = createDtoWithSeats(1);

            when(programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(PROGRAM_ID))
                    .thenReturn(createShowTime());
            when(ticketCategoryService.selectTicketCategoryListByProgramIdMultipleCache(eq(PROGRAM_ID), any()))
                    .thenReturn(List.of(createTicketCategory(TICKET_CATEGORY_ID)));
            when(seatService.selectSeatResolution(anyLong(), anyLong(), anyLong(), any()))
                    .thenReturn(List.of(createSeatVo(50L, 1, 1, TICKET_CATEGORY_ID)));
            Map<String, Long> remainMap = new HashMap<>();
            remainMap.put(String.valueOf(TICKET_CATEGORY_ID), 100L);
            when(ticketCategoryService.getRedisRemainNumberResolution(PROGRAM_ID, TICKET_CATEGORY_ID))
                    .thenReturn(remainMap);
            when(uidGenerator.getUid()).thenReturn(888L, 12345L);

            List<PurchaseSeat> purchaseSeats = List.of(createPurchaseSeat(50L, 2000L, TICKET_CATEGORY_ID));
            ProgramCacheCreateOrderData luaResult = new ProgramCacheCreateOrderData();
            luaResult.setCode(BaseCode.SUCCESS.getCode());
            luaResult.setPurchaseSeatList(purchaseSeats);
            when(programCacheCreateOrderResolutionOperate.programCacheOperate(anyList(), any()))
                    .thenReturn(luaResult);
            when(programService.simpleGetProgramAndShowMultipleCache(PROGRAM_ID))
                    .thenReturn(createProgramVo());

            doAnswer(invocation -> {
                FailureCallback failureCallback = invocation.getArgument(2);
                failureCallback.onFailure(new RuntimeException("kafka down"));
                return null;
            }).when(createOrderSend).sendMessage(anyString(), any(), any());

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> programOrderService.createNewAsync(dto, ProgramOrderVersion.V4_VERSION.getValue()));

            // Kafka 发送失败：回滚 Redis 缓存（释放已锁座位、恢复余票）
            verify(programCacheResolutionOperate).programCacheOperate(anyList(), any());
        }

        @Test
        void sendTimeout_DegradesToAcceptedAndWritesPending() {
            ProgramOrderCreateDto dto = createDtoWithSeats(1);

            when(programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(PROGRAM_ID))
                    .thenReturn(createShowTime());
            when(ticketCategoryService.selectTicketCategoryListByProgramIdMultipleCache(eq(PROGRAM_ID), any()))
                    .thenReturn(List.of(createTicketCategory(TICKET_CATEGORY_ID)));
            when(seatService.selectSeatResolution(anyLong(), anyLong(), anyLong(), any()))
                    .thenReturn(List.of(createSeatVo(50L, 1, 1, TICKET_CATEGORY_ID)));
            Map<String, Long> remainMap = new HashMap<>();
            remainMap.put(String.valueOf(TICKET_CATEGORY_ID), 100L);
            when(ticketCategoryService.getRedisRemainNumberResolution(PROGRAM_ID, TICKET_CATEGORY_ID))
                    .thenReturn(remainMap);
            when(uidGenerator.getUid()).thenReturn(888L, 12345L);

            List<PurchaseSeat> purchaseSeats = List.of(createPurchaseSeat(50L, 2000L, TICKET_CATEGORY_ID));
            ProgramCacheCreateOrderData luaResult = new ProgramCacheCreateOrderData();
            luaResult.setCode(BaseCode.SUCCESS.getCode());
            luaResult.setPurchaseSeatList(purchaseSeats);
            when(programCacheCreateOrderResolutionOperate.programCacheOperate(anyList(), any()))
                    .thenReturn(luaResult);
            when(programService.simpleGetProgramAndShowMultipleCache(PROGRAM_ID))
                    .thenReturn(createProgramVo());

            // sendMessage 永不回调成功/失败 → latch.await 超时 → 降级为已受理并写 PENDING
            doNothing().when(createOrderSend).sendMessage(anyString(), any(), any());

            ArgumentCaptor<RedisKeyBuild> pendingKeyCaptor = ArgumentCaptor.forClass(RedisKeyBuild.class);
            ArgumentCaptor<PendingOrder> pendingCaptor = ArgumentCaptor.forClass(PendingOrder.class);
            String result = programOrderService.createNewAsync(dto, ProgramOrderVersion.V4_VERSION.getValue());

            assertNotNull(result);
            // 降级路径：写入 PENDING 待确认队列（不做缓存回滚，回滚由 A2 对账任务裁决）
            verify(redisCache).leftPushForList(pendingKeyCaptor.capture(), pendingCaptor.capture());
            assertTrue(pendingKeyCaptor.getValue().getRelKey().contains("d_mai_order_create_pending"));
            assertEquals(result, String.valueOf(pendingCaptor.getValue().getOrderCreateMq().getOrderNumber()));
            // 未走发送失败回滚路径
            verify(programCacheResolutionOperate, never()).programCacheOperate(anyList(), any());
        }
    }

    // ==================== P1: createNewAsyncV5 (V5) ====================

    @Nested
    class CreateNewAsyncV5 {

        @Test
        void v5Lua幂等标记TTL为3秒() {
            ProgramOrderCreateDto dto = createDtoWithSeats(1);

            when(programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(PROGRAM_ID))
                    .thenReturn(createShowTime());
            when(ticketCategoryService.selectTicketCategoryListByProgramIdMultipleCache(eq(PROGRAM_ID), any()))
                    .thenReturn(List.of(createTicketCategory(TICKET_CATEGORY_ID)));
            when(seatService.selectSeatResolution(anyLong(), anyLong(), anyLong(), any()))
                    .thenReturn(List.of(createSeatVo(50L, 1, 1, TICKET_CATEGORY_ID)));
            Map<String, Long> remainMap = new HashMap<>();
            remainMap.put(String.valueOf(TICKET_CATEGORY_ID), 100L);
            when(ticketCategoryService.getRedisRemainNumberResolution(PROGRAM_ID, TICKET_CATEGORY_ID))
                    .thenReturn(remainMap);
            // 本地库存闸门未跟踪（-1=不拦截），避免售罄短路
            when(programLocalStockGate.estimatedRemain(PROGRAM_ID, TICKET_CATEGORY_ID)).thenReturn(-1);
            when(uidGenerator.getUid()).thenReturn(888L);

            List<PurchaseSeat> purchaseSeats = List.of(createPurchaseSeat(50L, 2000L, TICKET_CATEGORY_ID));
            ProgramCacheCreateOrderData luaResult = new ProgramCacheCreateOrderData();
            luaResult.setCode(BaseCode.SUCCESS.getCode());
            luaResult.setPurchaseSeatList(purchaseSeats);
            when(programCacheCreateOrderV5ResolutionOperate.programCacheOperate(anyList(), any()))
                    .thenReturn(luaResult);

            programOrderService.createOrderOperateProgramCacheResolutionV5(dto);

            // 幂等标记 TTL 通过 ARGV[4]（data[3]）传入 V5 Lua，必须为 3 秒
            ArgumentCaptor<String[]> dataCaptor = ArgumentCaptor.forClass(String[].class);
            verify(programCacheCreateOrderV5ResolutionOperate).programCacheOperate(anyList(), dataCaptor.capture());
            assertEquals("3", dataCaptor.getValue()[3]);
        }
    }

    // ==================== P1: updateProgramCacheDataResolution ====================

    @Nested
    class UpdateProgramCacheDataResolution {

        @Test
        void withInvalidStatus_ThrowsException() throws Exception {
            List<SeatVo> seats = List.of(createSeatVo(1L, 1, 1, TICKET_CATEGORY_ID));

            java.lang.reflect.Method method = ProgramOrderService.class
                    .getDeclaredMethod("updateProgramCacheDataResolution",
                            Long.class, List.class, OrderStatus.class);
            method.setAccessible(true);
            java.lang.reflect.InvocationTargetException ex = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> method.invoke(programOrderService, PROGRAM_ID, seats, OrderStatus.PAY));
            assertInstanceOf(TicketFlowFrameException.class, ex.getCause());
            assertEquals(BaseCode.OPERATE_ORDER_STATUS_NOT_PERMIT.getCode(),
                    ((TicketFlowFrameException) ex.getCause()).getCode());
        }

        @Test
        void noPay_Success() throws Exception {
            List<SeatVo> seats = List.of(createSeatVo(1L, 1, 1, TICKET_CATEGORY_ID));

            java.lang.reflect.Method method = ProgramOrderService.class
                    .getDeclaredMethod("updateProgramCacheDataResolution",
                            Long.class, List.class, OrderStatus.class);
            method.setAccessible(true);
            method.invoke(programOrderService, PROGRAM_ID, seats, OrderStatus.NO_PAY);

            verify(programCacheResolutionOperate).programCacheOperate(anyList(), any());
        }

        @Test
        void cancel_Success() throws Exception {
            SeatVo seat = createSeatVo(1L, 1, 1, TICKET_CATEGORY_ID);
            List<SeatVo> seats = List.of(seat);

            java.lang.reflect.Method method = ProgramOrderService.class
                    .getDeclaredMethod("updateProgramCacheDataResolution",
                            Long.class, List.class, OrderStatus.class);
            method.setAccessible(true);
            method.invoke(programOrderService, PROGRAM_ID, seats, OrderStatus.CANCEL);

            verify(programCacheResolutionOperate).programCacheOperate(anyList(), any());
        }
    }

    // ==================== P1: create (V1/V2 sync) ====================

    @Nested
    class Create {

        @Test
        void withSeatSelection_Success() {
            ProgramOrderCreateDto dto = createDtoWithSeats(2);

            when(programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(PROGRAM_ID))
                    .thenReturn(createShowTime());
            when(ticketCategoryService.selectTicketCategoryListByProgramIdMultipleCache(eq(PROGRAM_ID), any()))
                    .thenReturn(List.of(createTicketCategory(TICKET_CATEGORY_ID)));

            SeatVo seatVo1 = createSeatVo(50L, 1, 1, TICKET_CATEGORY_ID);
            SeatVo seatVo2 = createSeatVo(51L, 1, 2, TICKET_CATEGORY_ID);
            when(seatService.selectSeatResolution(anyLong(), anyLong(), anyLong(), any()))
                    .thenReturn(List.of(seatVo1, seatVo2));
            Map<String, Long> remainMap = new HashMap<>();
            remainMap.put(String.valueOf(TICKET_CATEGORY_ID), 100L);
            when(ticketCategoryService.getRedisRemainNumberResolution(PROGRAM_ID, TICKET_CATEGORY_ID))
                    .thenReturn(remainMap);
            when(uidGenerator.getUid()).thenReturn(888L, 12345L);
            when(uidGenerator.getOrderNumber(USER_ID)).thenReturn(20240999L);

            List<PurchaseSeat> purchaseSeats = Arrays.asList(
                    createPurchaseSeat(50L, 2000L, TICKET_CATEGORY_ID),
                    createPurchaseSeat(51L, 2001L, TICKET_CATEGORY_ID));
            ProgramCacheCreateOrderData luaResult = new ProgramCacheCreateOrderData();
            luaResult.setCode(BaseCode.SUCCESS.getCode());
            luaResult.setPurchaseSeatList(purchaseSeats);
            when(programCacheCreateOrderResolutionOperate.programCacheOperate(anyList(), any()))
                    .thenReturn(luaResult);

            when(programService.simpleGetProgramAndShowMultipleCache(PROGRAM_ID))
                    .thenReturn(createProgramVo());
            when(orderClient.create(any(OrderCreateDto.class)))
                    .thenReturn(ApiResponse.ok("2024001"));

            String result = programOrderService.create(dto, ProgramOrderVersion.V1_VERSION.getValue());

            assertEquals("2024001", result);
            verify(delayOrderCancelSend).sendMessage(any(DelayOrderCancelDto.class));

            // 参数组装：主单字段、总价、逐购票人座位与票价信息
            ArgumentCaptor<OrderCreateDto> orderCaptor = ArgumentCaptor.forClass(OrderCreateDto.class);
            verify(orderClient).create(orderCaptor.capture());
            OrderCreateDto captured = orderCaptor.getValue();
            assertEquals(20240999L, captured.getOrderNumber());
            assertEquals(PROGRAM_ID, captured.getProgramId());
            assertEquals(USER_ID, captured.getUserId());
            assertEquals("颜人中「MOMENT\u207F」演唱会-北京站", captured.getProgramTitle());
            assertEquals("华熙LIVE", captured.getProgramPlace());
            assertEquals("https://picsum.photos/seed/yanrenzhong/800/400", captured.getProgramItemPicture());
            assertEquals(1, captured.getProgramPermitChooseSeat());
            assertEquals(new BigDecimal("200"), captured.getOrderPrice());
            assertEquals(ProgramOrderVersion.V1_VERSION.getValue(), captured.getOrderVersion());
            assertEquals(2, captured.getOrderTicketUserCreateDtoList().size());

            OrderTicketUserCreateDto firstTicketUser = captured.getOrderTicketUserCreateDtoList().get(0);
            assertEquals(20240999L, firstTicketUser.getOrderNumber());
            assertEquals(PROGRAM_ID, firstTicketUser.getProgramId());
            assertEquals(USER_ID, firstTicketUser.getUserId());
            assertEquals(2000L, firstTicketUser.getTicketUserId());
            assertEquals(50L, firstTicketUser.getSeatId());
            assertEquals("1排50列", firstTicketUser.getSeatInfo());
            assertEquals(TICKET_CATEGORY_ID, firstTicketUser.getTicketCategoryId());
            assertEquals(new BigDecimal("100"), firstTicketUser.getOrderPrice());

            OrderTicketUserCreateDto secondTicketUser = captured.getOrderTicketUserCreateDtoList().get(1);
            assertEquals(2001L, secondTicketUser.getTicketUserId());
            assertEquals(51L, secondTicketUser.getSeatId());
            assertEquals("1排51列", secondTicketUser.getSeatInfo());
            assertEquals(TICKET_CATEGORY_ID, secondTicketUser.getTicketCategoryId());
            assertEquals(new BigDecimal("100"), secondTicketUser.getOrderPrice());
        }

        @Test
        void whenRemainNumberInsufficient_ThrowsException() {
            ProgramOrderCreateDto dto = createDtoWithSeats(2);

            when(programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(PROGRAM_ID))
                    .thenReturn(createShowTime());
            when(ticketCategoryService.selectTicketCategoryListByProgramIdMultipleCache(eq(PROGRAM_ID), any()))
                    .thenReturn(List.of(createTicketCategory(TICKET_CATEGORY_ID)));

            SeatVo seatVo1 = createSeatVo(50L, 1, 1, TICKET_CATEGORY_ID);
            SeatVo seatVo2 = createSeatVo(51L, 1, 2, TICKET_CATEGORY_ID);
            when(seatService.selectSeatResolution(anyLong(), anyLong(), anyLong(), any()))
                    .thenReturn(List.of(seatVo1, seatVo2));
            Map<String, Long> remainMap = new HashMap<>();
            remainMap.put(String.valueOf(TICKET_CATEGORY_ID), 1L);
            when(ticketCategoryService.getRedisRemainNumberResolution(PROGRAM_ID, TICKET_CATEGORY_ID))
                    .thenReturn(remainMap);
            when(uidGenerator.getUid()).thenReturn(888L);

            ProgramCacheCreateOrderData luaResult = new ProgramCacheCreateOrderData();
            luaResult.setCode(BaseCode.TICKET_REMAIN_NUMBER_NOT_SUFFICIENT.getCode());
            when(programCacheCreateOrderResolutionOperate.programCacheOperate(anyList(), any()))
                    .thenReturn(luaResult);

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> programOrderService.create(dto, ProgramOrderVersion.V1_VERSION.getValue()));
            assertEquals(BaseCode.TICKET_REMAIN_NUMBER_NOT_SUFFICIENT.getCode(), ex.getCode());
        }

        @Test
        void whenPriceMismatch_ThrowsException() {
            ProgramOrderCreateDto dto = createDtoWithSeats(1);

            when(programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(PROGRAM_ID))
                    .thenReturn(createShowTime());
            when(ticketCategoryService.selectTicketCategoryListByProgramIdMultipleCache(eq(PROGRAM_ID), any()))
                    .thenReturn(List.of(createTicketCategory(TICKET_CATEGORY_ID)));

            SeatVo seatVo = createSeatVo(50L, 1, 1, TICKET_CATEGORY_ID);
            when(seatService.selectSeatResolution(anyLong(), anyLong(), anyLong(), any()))
                    .thenReturn(List.of(seatVo));
            Map<String, Long> remainMap = new HashMap<>();
            remainMap.put(String.valueOf(TICKET_CATEGORY_ID), 100L);
            when(ticketCategoryService.getRedisRemainNumberResolution(PROGRAM_ID, TICKET_CATEGORY_ID))
                    .thenReturn(remainMap);
            when(uidGenerator.getUid()).thenReturn(888L);

            ProgramCacheCreateOrderData luaResult = new ProgramCacheCreateOrderData();
            luaResult.setCode(BaseCode.PRICE_ERROR.getCode());
            when(programCacheCreateOrderResolutionOperate.programCacheOperate(anyList(), any()))
                    .thenReturn(luaResult);

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> programOrderService.create(dto, ProgramOrderVersion.V1_VERSION.getValue()));
            assertEquals(BaseCode.PRICE_ERROR.getCode(), ex.getCode());
        }

        @Test
        void whenOrderClientFails_RollsBackCache() {
            ProgramOrderCreateDto dto = createDtoWithSeats(1);

            when(programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(PROGRAM_ID))
                    .thenReturn(createShowTime());
            when(ticketCategoryService.selectTicketCategoryListByProgramIdMultipleCache(eq(PROGRAM_ID), any()))
                    .thenReturn(List.of(createTicketCategory(TICKET_CATEGORY_ID)));

            SeatVo seatVo = createSeatVo(50L, 1, 1, TICKET_CATEGORY_ID);
            when(seatService.selectSeatResolution(anyLong(), anyLong(), anyLong(), any()))
                    .thenReturn(List.of(seatVo));
            Map<String, Long> remainMap = new HashMap<>();
            remainMap.put(String.valueOf(TICKET_CATEGORY_ID), 100L);
            when(ticketCategoryService.getRedisRemainNumberResolution(PROGRAM_ID, TICKET_CATEGORY_ID))
                    .thenReturn(remainMap);
            when(uidGenerator.getUid()).thenReturn(888L, 12345L);

            List<PurchaseSeat> purchaseSeats = List.of(createPurchaseSeat(50L, 2000L, TICKET_CATEGORY_ID));
            ProgramCacheCreateOrderData luaResult = new ProgramCacheCreateOrderData();
            luaResult.setCode(BaseCode.SUCCESS.getCode());
            luaResult.setPurchaseSeatList(purchaseSeats);
            when(programCacheCreateOrderResolutionOperate.programCacheOperate(anyList(), any()))
                    .thenReturn(luaResult);
            when(programService.simpleGetProgramAndShowMultipleCache(PROGRAM_ID))
                    .thenReturn(createProgramVo());
            when(orderClient.create(any(OrderCreateDto.class)))
                    .thenReturn(ApiResponse.error("创建订单失败"));

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> programOrderService.create(dto, ProgramOrderVersion.V1_VERSION.getValue()));

            // Lua 扣减成功后 RPC 失败，仅触发一次补偿回滚（无校验 Lua 回补 CANCEL）
            verify(programCacheResolutionOperate).programCacheOperate(anyList(), any());
        }

        @Test
        void whenBuyerCountExceedsSeatCount_ThrowsSeatNotExist() {
            ProgramOrderCreateDto dto = createDtoWithSeats(2);
            // 购票人 2 个但 Lua 只返回 1 个座位：V1 按索引取座位应抛业务异常而非 IOOBE
            dto.getTicketUserIdList().clear();
            dto.getTicketUserIdList().addAll(List.of(2000L, 2001L));

            when(programShowTimeService.selectProgramShowTimeByProgramIdMultipleCache(PROGRAM_ID))
                    .thenReturn(createShowTime());
            when(ticketCategoryService.selectTicketCategoryListByProgramIdMultipleCache(eq(PROGRAM_ID), any()))
                    .thenReturn(List.of(createTicketCategory(TICKET_CATEGORY_ID)));
            when(seatService.selectSeatResolution(anyLong(), anyLong(), anyLong(), any()))
                    .thenReturn(List.of(createSeatVo(50L, 1, 1, TICKET_CATEGORY_ID)));
            Map<String, Long> remainMap = new HashMap<>();
            remainMap.put(String.valueOf(TICKET_CATEGORY_ID), 100L);
            when(ticketCategoryService.getRedisRemainNumberResolution(PROGRAM_ID, TICKET_CATEGORY_ID))
                    .thenReturn(remainMap);
            when(uidGenerator.getUid()).thenReturn(888L, 12345L);

            List<PurchaseSeat> purchaseSeats = List.of(createPurchaseSeat(50L, 2000L, TICKET_CATEGORY_ID));
            ProgramCacheCreateOrderData luaResult = new ProgramCacheCreateOrderData();
            luaResult.setCode(BaseCode.SUCCESS.getCode());
            luaResult.setPurchaseSeatList(purchaseSeats);
            when(programCacheCreateOrderResolutionOperate.programCacheOperate(anyList(), any()))
                    .thenReturn(luaResult);
            when(programService.simpleGetProgramAndShowMultipleCache(PROGRAM_ID))
                    .thenReturn(createProgramVo());

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> programOrderService.create(dto, ProgramOrderVersion.V1_VERSION.getValue()));
            assertEquals(BaseCode.SEAT_NOT_EXIST.getCode(), ex.getCode());
        }
    }

    // ==================== P2: createProgramRecordTask ====================

    @Nested
    class CreateProgramRecordTask {

        @Test
        void success_InsertsRecord() {
            when(uidGenerator.getUid()).thenReturn(999L);

            programOrderService.createProgramRecordTask(PROGRAM_ID);

            ArgumentCaptor<ProgramRecordTask> captor = ArgumentCaptor.forClass(ProgramRecordTask.class);
            verify(programRecordTaskMapper).insert(captor.capture());
            assertEquals(PROGRAM_ID, captor.getValue().getProgramId());
            assertEquals(999L, captor.getValue().getId().longValue());
        }
    }
}
