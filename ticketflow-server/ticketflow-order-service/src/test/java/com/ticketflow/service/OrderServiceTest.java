package com.ticketflow.service;

import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ticketflow.client.PayClient;
import com.ticketflow.client.ProgramClient;
import com.ticketflow.client.UserClient;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.domain.OrderCreateDomain;
import com.ticketflow.domain.OrderCreateMq;
import com.ticketflow.dto.*;
import com.ticketflow.entity.Order;
import com.ticketflow.entity.OrderProgram;
import com.ticketflow.entity.OrderTicketUser;
import com.ticketflow.entity.OrderTicketUserAggregate;
import com.ticketflow.enums.*;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.mapper.OrderMapper;
import com.ticketflow.mapper.OrderProgramMapper;
import com.ticketflow.mapper.OrderTicketUserMapper;
import com.ticketflow.mapper.OrderTicketUserRecordMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.request.CustomizeRequestWrapper;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.service.delaysend.DelayOperateProgramDataSend;
import com.ticketflow.service.properties.OrderProperties;
import com.ticketflow.util.DateUtils;
import com.ticketflow.util.ServiceLockTool;
import com.ticketflow.vo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import com.ticketflow.core.SpringUtil;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.ticketflow.constant.Constant.ALIPAY_NOTIFY_SUCCESS_RESULT;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Spy
    @InjectMocks
    private OrderService orderService;

    @Mock private UidGenerator uidGenerator;
    @Mock private OrderMapper orderMapper;
    @Mock private OrderTicketUserMapper orderTicketUserMapper;
    @Mock private OrderTicketUserService orderTicketUserService;
    @Mock private OrderTicketUserRecordService orderTicketUserRecordService;
    @Mock private OrderProgramCacheResolutionOperate orderProgramCacheResolutionOperate;
    @Mock private RedisCache redisCache;
    @Mock private PayClient payClient;
    @Mock private UserClient userClient;
    @Mock private OrderProperties orderProperties;
    @Mock private ServiceLockTool serviceLockTool;
    @Mock private ProgramClient programClient;
    @Mock private OrderTicketUserRecordMapper orderTicketUserRecordMapper;
    @Mock private OrderProgramMapper orderProgramMapper;
    @Mock private DelayOperateProgramDataSend delayOperateProgramDataSend;

    private static final Long ORDER_NUMBER = 2024001L;
    private static final Long USER_ID = 1001L;
    private static final Long PROGRAM_ID = 501L;
    private static final Long IDENTIFIER_ID = 999L;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(orderService, "orderService", orderService);
        mockSpringUtil();
    }

    private static void mockSpringUtil() {
        ConfigurableApplicationContext mockContext = mock(ConfigurableApplicationContext.class);
        ConfigurableEnvironment mockEnv = mock(ConfigurableEnvironment.class);
        lenient().when(mockContext.getEnvironment()).thenReturn(mockEnv);
        lenient().when(mockEnv.getProperty(eq("prefix.distinction.name"), anyString())).thenReturn("test");
        ReflectionTestUtils.setField(SpringUtil.class, "configurableApplicationContext", mockContext);
    }

    private Order createOrder(Integer status) {
        Order order = new Order();
        order.setId(1L);
        order.setOrderNumber(ORDER_NUMBER);
        order.setProgramId(PROGRAM_ID);
        order.setUserId(USER_ID);
        order.setIdentifierId(IDENTIFIER_ID);
        order.setOrderStatus(status);
        order.setOrderPrice(new BigDecimal("200"));
        order.setProgramPermitChooseSeat(BusinessStatus.YES.getCode());
        order.setOrderVersion(ProgramOrderVersion.V3_VERSION.getValue());
        return order;
    }

    private List<OrderTicketUser> createTicketUserList() {
        OrderTicketUser tu1 = new OrderTicketUser();
        tu1.setId(10L);
        tu1.setOrderNumber(ORDER_NUMBER);
        tu1.setProgramId(PROGRAM_ID);
        tu1.setUserId(USER_ID);
        tu1.setTicketUserId(2001L);
        tu1.setSeatId(51L);
        tu1.setSeatInfo("1排1列");
        tu1.setTicketCategoryId(5L);
        tu1.setOrderPrice(new BigDecimal("100"));
        tu1.setOrderStatus(OrderStatus.NO_PAY.getCode());

        OrderTicketUser tu2 = new OrderTicketUser();
        tu2.setId(11L);
        tu2.setOrderNumber(ORDER_NUMBER);
        tu2.setProgramId(PROGRAM_ID);
        tu2.setUserId(USER_ID);
        tu2.setTicketUserId(2002L);
        tu2.setSeatId(52L);
        tu2.setSeatInfo("1排2列");
        tu2.setTicketCategoryId(5L);
        tu2.setOrderPrice(new BigDecimal("100"));
        tu2.setOrderStatus(OrderStatus.NO_PAY.getCode());

        return Arrays.asList(tu1, tu2);
    }

    private OrderTicketUserCreateDto createTicketUserDto(Long ticketUserId, Long seatId) {
        OrderTicketUserCreateDto dto = new OrderTicketUserCreateDto();
        dto.setOrderNumber(ORDER_NUMBER);
        dto.setProgramId(PROGRAM_ID);
        dto.setUserId(USER_ID);
        dto.setTicketUserId(ticketUserId);
        dto.setSeatId(seatId);
        dto.setSeatInfo("1排1列");
        dto.setTicketCategoryId(5L);
        dto.setOrderPrice(new BigDecimal("100"));
        dto.setCreateOrderTime(DateUtils.now());
        return dto;
    }

    // ==================== P0: checkOrderStatus ====================

    @Nested
    class CheckOrderStatus {

        @Test
        void withNullOrder_ThrowsException() {
            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.checkOrderStatus(null));
            assertEquals(BaseCode.ORDER_NOT_EXIST.getCode(), ex.getCode());
        }

        @Test
        void withCancelledOrder_ThrowsException() {
            Order order = createOrder(OrderStatus.CANCEL.getCode());
            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.checkOrderStatus(order));
            assertEquals(BaseCode.ORDER_CANCEL.getCode(), ex.getCode());
        }

        @Test
        void withPaidOrder_ThrowsException() {
            Order order = createOrder(OrderStatus.PAY.getCode());
            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.checkOrderStatus(order));
            assertEquals(BaseCode.ORDER_PAY.getCode(), ex.getCode());
        }

        @Test
        void withRefundedOrder_ThrowsException() {
            Order order = createOrder(OrderStatus.REFUND.getCode());
            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.checkOrderStatus(order));
            assertEquals(BaseCode.ORDER_REFUND.getCode(), ex.getCode());
        }

        @Test
        void withUnpaidOrder_Passes() {
            Order order = createOrder(OrderStatus.NO_PAY.getCode());
            assertDoesNotThrow(() -> orderService.checkOrderStatus(order));
        }
    }

    // ==================== P0: doCreate ====================

    @Nested
    class DoCreate {

        @Test
        void whenOrderNumberExists_ThrowsException() {
            OrderCreateDomain domain = new OrderCreateDomain();
            domain.setOrderNumber(ORDER_NUMBER);
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(new Order());

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.doCreate(domain));
            assertEquals(BaseCode.ORDER_EXIST.getCode(), ex.getCode());
        }

        @Test
        void success_CreatesOrderAndRecords() {
            OrderTicketUserCreateDto dto1 = createTicketUserDto(2001L, 51L);
            OrderTicketUserCreateDto dto2 = createTicketUserDto(2002L, 52L);

            OrderCreateDomain domain = new OrderCreateDomain();
            domain.setOrderNumber(ORDER_NUMBER);
            domain.setProgramId(PROGRAM_ID);
            domain.setUserId(USER_ID);
            domain.setIdentifierId(IDENTIFIER_ID);
            domain.setProgramItemPicture("pic.jpg");
            domain.setProgramTitle("演唱会");
            domain.setProgramPlace("北京");
            domain.setProgramShowTime(new Date());
            domain.setProgramPermitChooseSeat(BusinessStatus.YES.getCode());
            domain.setOrderPrice(new BigDecimal("200"));
            domain.setCreateOrderTime(new Date());
            domain.setOrderVersion(ProgramOrderVersion.V3_VERSION.getValue());
            domain.setOrderTicketUserCreateDtoList(Arrays.asList(dto1, dto2));

            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(uidGenerator.getUid()).thenReturn(1L, 2L, 3L, 4L, 5L);
            when(orderTicketUserService.saveBatch(anyList())).thenReturn(true);
            when(orderTicketUserRecordService.saveBatch(anyList())).thenReturn(true);

            String result = orderService.doCreate(domain);

            assertEquals(String.valueOf(ORDER_NUMBER), result);
            verify(orderMapper).insert(any(Order.class));
            verify(orderProgramMapper).insert(any(OrderProgram.class));
            verify(orderTicketUserService).saveBatch(argThat(list -> ((List<?>) list).size() == 2));
            verify(orderTicketUserRecordService).saveBatch(argThat(list -> ((List<?>) list).size() == 2));
            verify(redisCache).incrBy(any(RedisKeyBuild.class), eq(2L));
        }
    }

    // ==================== P0: cancel ====================

    @Nested
    class Cancel {

        @Test
        void success_DelegatesToUpdateOrderRelatedData() {
            OrderCancelDto dto = new OrderCancelDto();
            dto.setOrderNumber(ORDER_NUMBER);

            doNothing().when(orderService).updateOrderRelatedData(ORDER_NUMBER, OrderStatus.CANCEL);

            boolean result = orderService.cancel(dto);

            assertTrue(result);
            verify(orderService).updateOrderRelatedData(ORDER_NUMBER, OrderStatus.CANCEL);
        }
    }

    // ==================== P0: initiateCancel ====================

    @Nested
    class InitiateCancel {

        @Test
        void whenOrderNotFound_ThrowsException() {
            OrderCancelDto dto = new OrderCancelDto();
            dto.setOrderNumber(ORDER_NUMBER);
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.initiateCancel(dto));
            assertEquals(BaseCode.ORDER_NOT_EXIST.getCode(), ex.getCode());
        }

        @Test
        void whenOrderNotUnpaid_ThrowsException() {
            OrderCancelDto dto = new OrderCancelDto();
            dto.setOrderNumber(ORDER_NUMBER);
            Order order = createOrder(OrderStatus.PAY.getCode());
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.initiateCancel(dto));
            assertEquals(BaseCode.CAN_NOT_CANCEL.getCode(), ex.getCode());
        }

        @Test
        void success_CallsCancel() {
            OrderCancelDto dto = new OrderCancelDto();
            dto.setOrderNumber(ORDER_NUMBER);
            Order order = createOrder(OrderStatus.NO_PAY.getCode());
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
            doReturn(true).when(orderService).cancel(dto);

            boolean result = orderService.initiateCancel(dto);

            assertTrue(result);
            verify(orderService).cancel(dto);
        }
    }

    // ==================== P0: updateOrderRelatedData ====================

    @Nested
    class UpdateOrderRelatedData {

        @Test
        void withInvalidStatus_ThrowsException() {
            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.updateOrderRelatedData(ORDER_NUMBER, OrderStatus.NO_PAY));
            assertEquals(BaseCode.OPERATE_ORDER_STATUS_NOT_PERMIT.getCode(), ex.getCode());
        }

        @Test
        void whenOrderNotFound_ThrowsException() {
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.updateOrderRelatedData(ORDER_NUMBER, OrderStatus.PAY));
            assertEquals(BaseCode.ORDER_NOT_EXIST.getCode(), ex.getCode());
        }

        @Test
        void whenOrderAlreadyCancelled_ThrowsException() {
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(createOrder(OrderStatus.CANCEL.getCode()));

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.updateOrderRelatedData(ORDER_NUMBER, OrderStatus.PAY));
            assertEquals(BaseCode.ORDER_CANCEL.getCode(), ex.getCode());
        }

        @Test
        void whenOrderAlreadyPaid_ThrowsException() {
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(createOrder(OrderStatus.PAY.getCode()));

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.updateOrderRelatedData(ORDER_NUMBER, OrderStatus.CANCEL));
            assertEquals(BaseCode.ORDER_PAY.getCode(), ex.getCode());
        }

        @Test
        void whenOrderAlreadyRefunded_ThrowsException() {
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(createOrder(OrderStatus.REFUND.getCode()));

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.updateOrderRelatedData(ORDER_NUMBER, OrderStatus.PAY));
            assertEquals(BaseCode.ORDER_REFUND.getCode(), ex.getCode());
        }

        @Test
        void whenNoTicketUsers_ThrowsException() {
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(createOrder(OrderStatus.NO_PAY.getCode()));
            when(orderTicketUserMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(new ArrayList<>());

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.updateOrderRelatedData(ORDER_NUMBER, OrderStatus.PAY));
            assertEquals(BaseCode.TICKET_USER_ORDER_NOT_EXIST.getCode(), ex.getCode());
        }

        @Test
        void pay_Success() {
            Order order = createOrder(OrderStatus.NO_PAY.getCode());
            List<OrderTicketUser> ticketUsers = createTicketUserList();

            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
            when(orderTicketUserMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(ticketUsers);
            when(orderMapper.update(any(Order.class), any(LambdaUpdateWrapper.class))).thenReturn(1);
            when(orderTicketUserMapper.update(any(OrderTicketUser.class), any(LambdaUpdateWrapper.class))).thenReturn(2);
            when(orderTicketUserRecordService.saveBatch(anyList())).thenReturn(true);

            // Mock multiGetForHash - returns List<SeatVo> per ticket category
            SeatVo seatVo1 = new SeatVo();
            seatVo1.setId(51L);
            seatVo1.setTicketCategoryId(5L);
            seatVo1.setSellStatus(SellStatus.NO_SOLD.getCode());
            seatVo1.setPrice(new BigDecimal("100"));
            seatVo1.setRowCode(1);
            seatVo1.setColCode(1);

            SeatVo seatVo2 = new SeatVo();
            seatVo2.setId(52L);
            seatVo2.setTicketCategoryId(5L);
            seatVo2.setSellStatus(SellStatus.NO_SOLD.getCode());
            seatVo2.setPrice(new BigDecimal("100"));
            seatVo2.setRowCode(1);
            seatVo2.setColCode(2);

            when(redisCache.multiGetForHash(any(RedisKeyBuild.class), anyList(), eq(SeatVo.class)))
                    .thenReturn(Arrays.asList(seatVo1, seatVo2));

            orderService.updateOrderRelatedData(ORDER_NUMBER, OrderStatus.PAY);

            ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
            verify(orderMapper).update(orderCaptor.capture(), any(LambdaUpdateWrapper.class));
            assertEquals(OrderStatus.PAY.getCode(), orderCaptor.getValue().getOrderStatus());
            assertNotNull(orderCaptor.getValue().getPayOrderTime());

            ArgumentCaptor<OrderTicketUser> otuCaptor = ArgumentCaptor.forClass(OrderTicketUser.class);
            verify(orderTicketUserMapper).update(otuCaptor.capture(), any(LambdaUpdateWrapper.class));
            assertEquals(OrderStatus.PAY.getCode(), otuCaptor.getValue().getOrderStatus());

            verify(orderTicketUserRecordService).saveBatch(argThat(list -> ((List<?>) list).size() == 2));
            verify(orderProgramCacheResolutionOperate).programCacheReverseOperate(anyList(), any(), any(), any(), any());
            verify(delayOperateProgramDataSend).sendMessage(anyString());
            verify(redisCache, never()).incrBy(any(RedisKeyBuild.class), anyLong());
        }

        @Test
        void cancel_Success() {
            Order order = createOrder(OrderStatus.NO_PAY.getCode());
            List<OrderTicketUser> ticketUsers = createTicketUserList();

            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
            when(orderTicketUserMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(ticketUsers);
            when(orderMapper.update(any(Order.class), any(LambdaUpdateWrapper.class))).thenReturn(1);
            when(orderTicketUserMapper.update(any(OrderTicketUser.class), any(LambdaUpdateWrapper.class))).thenReturn(2);
            when(orderTicketUserRecordService.saveBatch(anyList())).thenReturn(true);

            SeatVo seatVo1 = new SeatVo();
            seatVo1.setId(51L);
            seatVo1.setTicketCategoryId(5L);
            seatVo1.setSellStatus(SellStatus.LOCK.getCode());
            seatVo1.setPrice(new BigDecimal("100"));
            seatVo1.setRowCode(1);
            seatVo1.setColCode(1);

            SeatVo seatVo2 = new SeatVo();
            seatVo2.setId(52L);
            seatVo2.setTicketCategoryId(5L);
            seatVo2.setSellStatus(SellStatus.LOCK.getCode());
            seatVo2.setPrice(new BigDecimal("100"));
            seatVo2.setRowCode(1);
            seatVo2.setColCode(2);

            when(redisCache.multiGetForHash(any(RedisKeyBuild.class), anyList(), eq(SeatVo.class)))
                    .thenReturn(Arrays.asList(seatVo1, seatVo2));

            orderService.updateOrderRelatedData(ORDER_NUMBER, OrderStatus.CANCEL);

            ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
            verify(orderMapper).update(orderCaptor.capture(), any(LambdaUpdateWrapper.class));
            assertEquals(OrderStatus.CANCEL.getCode(), orderCaptor.getValue().getOrderStatus());
            assertNotNull(orderCaptor.getValue().getCancelOrderTime());

            verify(redisCache).incrBy(any(RedisKeyBuild.class), eq(-2L));
            verify(orderProgramCacheResolutionOperate).programCacheReverseOperate(anyList(), any(), any(), any(), any());
            verify(delayOperateProgramDataSend, never()).sendMessage(anyString());
        }

        @Test
        void cancel_V4Version_UsesFeign() {
            Order order = createOrder(OrderStatus.NO_PAY.getCode());
            order.setOrderVersion(ProgramOrderVersion.V4_VERSION.getValue());
            List<OrderTicketUser> ticketUsers = createTicketUserList();

            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
            when(orderTicketUserMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(ticketUsers);
            when(orderMapper.update(any(Order.class), any(LambdaUpdateWrapper.class))).thenReturn(1);
            when(orderTicketUserMapper.update(any(OrderTicketUser.class), any(LambdaUpdateWrapper.class))).thenReturn(2);
            when(orderTicketUserRecordService.saveBatch(anyList())).thenReturn(true);
            when(programClient.operateProgramData(any(ProgramOperateDataDto.class)))
                    .thenReturn(ApiResponse.ok(true));

            SeatVo seatVo = new SeatVo();
            seatVo.setId(51L);
            seatVo.setTicketCategoryId(5L);
            seatVo.setSellStatus(SellStatus.LOCK.getCode());
            seatVo.setPrice(new BigDecimal("100"));
            seatVo.setRowCode(1);
            seatVo.setColCode(1);
            when(redisCache.multiGetForHash(any(RedisKeyBuild.class), anyList(), eq(SeatVo.class)))
                    .thenReturn(List.of(seatVo));

            orderService.updateOrderRelatedData(ORDER_NUMBER, OrderStatus.CANCEL);

                        verify(programClient).operateProgramData(any(ProgramOperateDataDto.class));
        }
    }

    // ==================== P1: pay ====================

    @Nested
    class Pay {

        private OrderPayDto createPayDto() {
            OrderPayDto dto = new OrderPayDto();
            dto.setOrderNumber(ORDER_NUMBER);
            dto.setPrice(new BigDecimal("200"));
            dto.setChannel("alipay");
            dto.setPayBillType(1);
            dto.setSubject("演唱会门票");
            dto.setPlatform(1);
            return dto;
        }

        @Test
        void whenOrderNotFound_ThrowsException() {
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.pay(createPayDto()));
            assertEquals(BaseCode.ORDER_NOT_EXIST.getCode(), ex.getCode());
        }

        @Test
        void whenOrderCancelled_ThrowsException() {
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(createOrder(OrderStatus.CANCEL.getCode()));

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.pay(createPayDto()));
            assertEquals(BaseCode.ORDER_CANCEL.getCode(), ex.getCode());
        }

        @Test
        void whenOrderPaid_ThrowsException() {
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(createOrder(OrderStatus.PAY.getCode()));

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.pay(createPayDto()));
            assertEquals(BaseCode.ORDER_PAY.getCode(), ex.getCode());
        }

        @Test
        void whenOrderRefunded_ThrowsException() {
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(createOrder(OrderStatus.REFUND.getCode()));

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.pay(createPayDto()));
            assertEquals(BaseCode.ORDER_REFUND.getCode(), ex.getCode());
        }

        @Test
        void whenPriceMismatch_ThrowsException() {
            Order order = createOrder(OrderStatus.NO_PAY.getCode());
            order.setOrderPrice(new BigDecimal("300"));
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);

            OrderPayDto payDto = createPayDto();
            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.pay(payDto));
            assertEquals(BaseCode.PAY_PRICE_NOT_EQUAL_ORDER_PRICE.getCode(), ex.getCode());
        }

        @Test
        void success_ReturnsPayUrl() {
            Order order = createOrder(OrderStatus.NO_PAY.getCode());
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
            when(orderProperties.getOrderPayNotifyUrl()).thenReturn("http://notify.url");
            when(orderProperties.getOrderPayReturnUrl()).thenReturn("http://return.url");
            when(payClient.commonPay(any(PayDto.class))).thenReturn(ApiResponse.ok("http://pay.url"));

            String result = orderService.pay(createPayDto());

            assertEquals("http://pay.url", result);
            verify(payClient).commonPay(argThat(dto ->
                    dto.getOrderNumber().equals(String.valueOf(ORDER_NUMBER))));
        }

        @Test
        void whenPayClientReturnsError_ThrowsException() {
            Order order = createOrder(OrderStatus.NO_PAY.getCode());
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
            when(orderProperties.getOrderPayNotifyUrl()).thenReturn("http://notify.url");
            when(orderProperties.getOrderPayReturnUrl()).thenReturn("http://return.url");
            when(payClient.commonPay(any(PayDto.class)))
                    .thenReturn(ApiResponse.error(BaseCode.PAY_ERROR));

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.pay(createPayDto()));
            assertEquals(BaseCode.PAY_ERROR.getCode(), ex.getCode());
        }
    }

    // ==================== P1: payCheck ====================

    @Nested
    class PayCheck {

        private OrderPayCheckDto createCheckDto() {
            OrderPayCheckDto dto = new OrderPayCheckDto();
            dto.setOrderNumber(ORDER_NUMBER);
            dto.setPayChannelType(PayChannel.ALIPAY.getCode());
            return dto;
        }

        @Test
        void whenOrderNotFound_ThrowsException() {
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.payCheck(createCheckDto()));
            assertEquals(BaseCode.ORDER_NOT_EXIST.getCode(), ex.getCode());
        }

        @Test
        void whenOrderCancelledAndRefundSuccess_ReturnsRefundStatus() {
            Order order = createOrder(OrderStatus.CANCEL.getCode());
            order.setOrderPrice(new BigDecimal("200"));
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
            when(payClient.refund(any(RefundDto.class))).thenReturn(ApiResponse.ok("refunded"));

            OrderPayCheckVo result = orderService.payCheck(createCheckDto());

            assertEquals(OrderStatus.REFUND.getCode(), result.getOrderStatus());
            assertNotNull(result.getCancelOrderTime());
            verify(payClient).refund(any(RefundDto.class));
            verify(orderMapper).update(any(Order.class), any(LambdaUpdateWrapper.class));
        }

        @Test
        void whenOrderCancelledAndRefundFailed_StillReturnsRefundStatus() {
            Order order = createOrder(OrderStatus.CANCEL.getCode());
            order.setOrderPrice(new BigDecimal("200"));
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
            when(payClient.refund(any(RefundDto.class))).thenReturn(ApiResponse.error("退款失败"));

            OrderPayCheckVo result = orderService.payCheck(createCheckDto());

            assertEquals(OrderStatus.REFUND.getCode(), result.getOrderStatus());
            assertNotNull(result.getCancelOrderTime());
            verify(payClient).refund(any(RefundDto.class));
            verify(orderMapper, never()).update(any(Order.class), any(LambdaUpdateWrapper.class));
        }

        @Test
        void whenPayBillStatusPay_UpdatesOrderToPay() {
            Order order = createOrder(OrderStatus.NO_PAY.getCode());
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
            when(payClient.tradeCheck(any(TradeCheckDto.class)))
                    .thenReturn(ApiResponse.ok(createTradeCheckVo(true, PayBillStatus.PAY.getCode())));

            doNothing().when(orderService).updateOrderRelatedData(ORDER_NUMBER, OrderStatus.PAY);

            OrderPayCheckVo result = orderService.payCheck(createCheckDto());

            assertEquals(PayBillStatus.PAY.getCode(), result.getOrderStatus());
            assertNotNull(result.getPayOrderTime());
            verify(orderService).updateOrderRelatedData(ORDER_NUMBER, OrderStatus.PAY);
        }

        @Test
        void whenPayBillStatusCancel_UpdatesOrderToCancel() {
            Order order = createOrder(OrderStatus.NO_PAY.getCode());
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
            when(payClient.tradeCheck(any(TradeCheckDto.class)))
                    .thenReturn(ApiResponse.ok(createTradeCheckVo(true, PayBillStatus.CANCEL.getCode())));

            doNothing().when(orderService).updateOrderRelatedData(ORDER_NUMBER, OrderStatus.CANCEL);

            OrderPayCheckVo result = orderService.payCheck(createCheckDto());

            assertEquals(PayBillStatus.CANCEL.getCode(), result.getOrderStatus());
            assertNotNull(result.getCancelOrderTime());
            verify(orderService).updateOrderRelatedData(ORDER_NUMBER, OrderStatus.CANCEL);
        }

        @Test
        void whenTradeCheckFails_ThrowsException() {
            Order order = createOrder(OrderStatus.NO_PAY.getCode());
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
            when(payClient.tradeCheck(any(TradeCheckDto.class)))
                    .thenReturn(ApiResponse.error(BaseCode.PAY_TRADE_CHECK_ERROR));

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.payCheck(createCheckDto()));
            assertEquals(BaseCode.PAY_TRADE_CHECK_ERROR.getCode(), ex.getCode());
        }

        @Test
        void whenTradeCheckNotSuccess_ThrowsException() {
            Order order = createOrder(OrderStatus.NO_PAY.getCode());
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
            when(payClient.tradeCheck(any(TradeCheckDto.class)))
                    .thenReturn(ApiResponse.ok(createTradeCheckVo(false, PayBillStatus.NO_PAY.getCode())));

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.payCheck(createCheckDto()));
            assertEquals(BaseCode.PAY_TRADE_CHECK_ERROR.getCode(), ex.getCode());
        }

        private TradeCheckVo createTradeCheckVo(boolean success, Integer payBillStatus) {
            TradeCheckVo vo = new TradeCheckVo();
            vo.setSuccess(success);
            vo.setPayBillStatus(payBillStatus);
            return vo;
        }
    }

    // ==================== P1: selectList ====================

    @Nested
    class SelectList {

        @Test
        void empty_ReturnsEmptyList() {
            OrderListDto dto = new OrderListDto();
            dto.setUserId(USER_ID);
            when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(new ArrayList<>());

            List<OrderListVo> result = orderService.selectList(dto);

            assertTrue(result.isEmpty());
        }

        @Test
        void success_ReturnsListWithCount() {
            OrderListDto dto = new OrderListDto();
            dto.setUserId(USER_ID);

            Order order = createOrder(OrderStatus.NO_PAY.getCode());
            when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(order));

            OrderTicketUserAggregate aggregate = new OrderTicketUserAggregate();
            aggregate.setOrderNumber(ORDER_NUMBER);
            aggregate.setOrderTicketUserCount(2);
            when(orderTicketUserMapper.selectOrderTicketUserAggregate(anyList()))
                    .thenReturn(List.of(aggregate));

            List<OrderListVo> result = orderService.selectList(dto);

            assertEquals(1, result.size());
            assertEquals(ORDER_NUMBER, result.get(0).getOrderNumber());
            assertEquals(Integer.valueOf(2), result.get(0).getTicketCount());
        }
    }

    // ==================== P1: get ====================

    @Nested
    class Get {

        @Test
        void whenOrderNotFound_ThrowsException() {
            OrderGetDto dto = new OrderGetDto();
            dto.setOrderNumber(ORDER_NUMBER);
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.get(dto));
            assertEquals(BaseCode.ORDER_NOT_EXIST.getCode(), ex.getCode());
        }

        @Test
        void whenNoTicketUsers_ThrowsException() {
            OrderGetDto dto = new OrderGetDto();
            dto.setOrderNumber(ORDER_NUMBER);
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(createOrder(OrderStatus.NO_PAY.getCode()));
            when(orderTicketUserMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(new ArrayList<>());

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.get(dto));
            assertEquals(BaseCode.TICKET_USER_ORDER_NOT_EXIST.getCode(), ex.getCode());
        }

        @Test
        void success_ReturnsOrderGetVo() {
            OrderGetDto dto = new OrderGetDto();
            dto.setOrderNumber(ORDER_NUMBER);

            Order order = createOrder(OrderStatus.NO_PAY.getCode());
            order.setProgramPermitChooseSeat(BusinessStatus.NO.getCode());
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);

            List<OrderTicketUser> ticketUsers = createTicketUserList();
            when(orderTicketUserMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(ticketUsers);

            UserGetAndTicketUserListVo userListVo = new UserGetAndTicketUserListVo();
            UserVo userVo = new UserVo();
            userVo.setId(USER_ID);
            userListVo.setUserVo(userVo);

            TicketUserVo tv1 = new TicketUserVo();
            tv1.setId(2001L);
            TicketUserVo tv2 = new TicketUserVo();
            tv2.setId(2002L);
            userListVo.setTicketUserVoList(Arrays.asList(tv1, tv2));

            when(userClient.getUserAndTicketUserList(any(UserGetAndTicketUserListDto.class)))
                    .thenReturn(ApiResponse.ok(userListVo));

            OrderGetVo result = orderService.get(dto);

            assertNotNull(result);
            assertEquals(ORDER_NUMBER, result.getOrderNumber());
            assertNotNull(result.getOrderTicketInfoVoList());
            assertEquals(1, result.getOrderTicketInfoVoList().size());
            assertNotNull(result.getUserAndTicketUserInfoVo());
        }

        @Test
        void whenRpcUserDataEmpty_ThrowsException() {
            OrderGetDto dto = new OrderGetDto();
            dto.setOrderNumber(ORDER_NUMBER);

            when(orderMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(createOrder(OrderStatus.NO_PAY.getCode()));
            when(orderTicketUserMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(createTicketUserList());
            when(userClient.getUserAndTicketUserList(any(UserGetAndTicketUserListDto.class)))
                    .thenReturn(ApiResponse.ok(null));

            assertThrows(TicketFlowFrameException.class, () -> orderService.get(dto));
        }
    }

    // ==================== P2: simpleList ====================

    @Nested
    class SimpleList {

        @Test
        void whenNoUserIdAndNoOrderNumber_ThrowsException() {
            OrderSimpleListDto dto = new OrderSimpleListDto();

            TicketFlowFrameException ex = assertThrows(TicketFlowFrameException.class,
                    () -> orderService.simpleList(dto));
            assertEquals(BaseCode.USER_ID_AND_ORDER_NUMBER_NOT_EXIST.getCode(), ex.getCode());
        }

        @Test
        void empty_ReturnsEmptyList() {
            OrderSimpleListDto dto = new OrderSimpleListDto();
            dto.setOrderNumber(ORDER_NUMBER);
            when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(new ArrayList<>());

            List<OrderListVo> result = orderService.simpleList(dto);

            assertTrue(result.isEmpty());
        }

        @Test
        void success_ReturnsList() {
            OrderSimpleListDto dto = new OrderSimpleListDto();
            dto.setUserId(USER_ID);
            when(orderMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(createOrder(OrderStatus.NO_PAY.getCode())));

            List<OrderListVo> result = orderService.simpleList(dto);

            assertEquals(1, result.size());
            assertEquals(USER_ID, result.get(0).getUserId());
        }
    }

    // ==================== P2: alipayNotify ====================

    @Nested
    class AlipayNotify {

        @Test
        void whenEmptyOutTradeNo_ReturnsFailure() throws Exception {
            MockHttpServletRequest mockRequest = new MockHttpServletRequest();
            mockRequest.setContent("".getBytes());

            String result = orderService.alipayNotify(
                    new CustomizeRequestWrapper(mockRequest));

            assertEquals("failure", result);
        }

        @Test
        void whenOrderNotFound_ThrowsException() throws Exception {
            when(serviceLockTool.getLock(any(), anyString(), any())).thenReturn(mock(RLock.class));

            MockHttpServletRequest mockRequest = new MockHttpServletRequest();
            mockRequest.setContent("out_trade_no=999999".getBytes());
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            assertThrows(TicketFlowFrameException.class,
                    () -> orderService.alipayNotify(new CustomizeRequestWrapper(mockRequest)));
        }

        @Test
        void whenOrderCancelled_RefundsAndReturnsSuccess() throws Exception {
            RLock mockLock = mock(RLock.class);
            when(serviceLockTool.getLock(any(), anyString(), any())).thenReturn(mockLock);

            Order order = createOrder(OrderStatus.CANCEL.getCode());
            order.setOrderPrice(new BigDecimal("200"));
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
            when(payClient.refund(any(RefundDto.class))).thenReturn(ApiResponse.ok("refunded"));

            MockHttpServletRequest mockRequest = new MockHttpServletRequest();
            mockRequest.setContent(("out_trade_no=" + ORDER_NUMBER).getBytes());

            String result = orderService.alipayNotify(new CustomizeRequestWrapper(mockRequest));

            assertEquals(ALIPAY_NOTIFY_SUCCESS_RESULT, result);
            verify(payClient).refund(any(RefundDto.class));
            verify(mockLock).lock();
            verify(mockLock).unlock();
        }

        @Test
        void whenPaySuccess_UpdatesOrderRelatedData() throws Exception {
            RLock mockLock = mock(RLock.class);
            when(serviceLockTool.getLock(any(), anyString(), any())).thenReturn(mockLock);

            Order order = createOrder(OrderStatus.NO_PAY.getCode());
            when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);

            NotifyVo notifyVo = new NotifyVo();
            notifyVo.setOutTradeNo(String.valueOf(ORDER_NUMBER));
            notifyVo.setPayResult(ALIPAY_NOTIFY_SUCCESS_RESULT);
            when(payClient.notify(any(NotifyDto.class))).thenReturn(ApiResponse.ok(notifyVo));

            doNothing().when(orderService).updateOrderRelatedData(ORDER_NUMBER, OrderStatus.PAY);

            MockHttpServletRequest mockRequest = new MockHttpServletRequest();
            mockRequest.setContent(("out_trade_no=" + ORDER_NUMBER).getBytes());

            String result = orderService.alipayNotify(new CustomizeRequestWrapper(mockRequest));

            assertEquals(ALIPAY_NOTIFY_SUCCESS_RESULT, result);
            verify(orderService).updateOrderRelatedData(ORDER_NUMBER, OrderStatus.PAY);
        }
    }

    // ==================== P2: accountOrderCount ====================

    @Nested
    class AccountOrderCount {

        @Test
        void success_ReturnsCount() {
            AccountOrderCountDto dto = new AccountOrderCountDto();
            dto.setUserId(USER_ID);
            dto.setProgramId(PROGRAM_ID);
            when(orderMapper.accountOrderCount(USER_ID, PROGRAM_ID)).thenReturn(5);

            AccountOrderCountVo result = orderService.accountOrderCount(dto);

            assertEquals(Integer.valueOf(5), result.getCount());
        }
    }

    // ==================== P2: createMq (V4/V41 Kafka consumer) ====================

    @Nested
    class CreateMq {

        @Test
        void whenProgramClientFails_ThrowsExceptionAndSavesDiscardOrder() {
            OrderTicketUserCreateDto dto1 = createTicketUserDto(2001L, 51L);
            OrderCreateMq mq = new OrderCreateMq();
            mq.setOrderNumber(ORDER_NUMBER);
            mq.setProgramId(PROGRAM_ID);
            mq.setUserId(USER_ID);
            mq.setOrderTicketUserCreateDtoList(List.of(dto1));

            when(programClient.operateSeatLockAndTicketCategoryRemainNumber(any(ReduceRemainNumberDto.class)))
                    .thenReturn(ApiResponse.error("fail"));

            assertThrows(TicketFlowFrameException.class,
                    () -> orderService.createMq(mq));

            verify(redisCache).leftPushForList(any(RedisKeyBuild.class), any());
        }

        @Test
        void success_CreatesOrder() {
            OrderTicketUserCreateDto dto1 = createTicketUserDto(2001L, 51L);
            OrderCreateMq mq = new OrderCreateMq();
            mq.setOrderNumber(ORDER_NUMBER);
            mq.setProgramId(PROGRAM_ID);
            mq.setUserId(USER_ID);
            mq.setProgramItemPicture("pic.jpg");
            mq.setProgramTitle("演唱会");
            mq.setProgramPlace("北京");
            mq.setProgramShowTime(new Date());
            mq.setProgramPermitChooseSeat(BusinessStatus.YES.getCode());
            mq.setOrderPrice(new BigDecimal("100"));
            mq.setCreateOrderTime(new Date());
            mq.setOrderVersion(ProgramOrderVersion.V4_VERSION.getValue());
            mq.setOrderTicketUserCreateDtoList(List.of(dto1));

            when(programClient.operateSeatLockAndTicketCategoryRemainNumber(any(ReduceRemainNumberDto.class)))
                    .thenReturn(ApiResponse.ok(true));
            doReturn(String.valueOf(ORDER_NUMBER)).when(orderService).createByMq(mq);

            String result = orderService.createMq(mq);

            assertEquals(String.valueOf(ORDER_NUMBER), result);
            verify(redisCache).set(any(RedisKeyBuild.class), eq(String.valueOf(ORDER_NUMBER)),
                    eq(1L), eq(TimeUnit.MINUTES));
        }
    }
}
