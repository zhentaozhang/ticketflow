package com.ticketflow.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baidu.fsg.uid.UidGenerator;
import com.ticketflow.client.PayClient;
import com.ticketflow.client.ProgramClient;
import com.ticketflow.client.UserClient;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.constant.Constant;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.core.SpringUtil;
import com.ticketflow.domain.OrderCreateDomain;
import com.ticketflow.domain.OrderCreateMq;
import com.ticketflow.domain.SeatIdAndTicketUserIdDomain;
import com.ticketflow.dto.*;
import com.ticketflow.entity.Order;
import com.ticketflow.entity.OrderProgram;
import com.ticketflow.entity.OrderTicketUser;
import com.ticketflow.entity.OrderTicketUserAggregate;
import com.ticketflow.entity.OrderTicketUserRecord;
import com.ticketflow.enums.*;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.mapper.OrderMapper;
import com.ticketflow.mapper.OrderProgramMapper;
import com.ticketflow.mapper.OrderTicketUserMapper;
import com.ticketflow.mapper.OrderTicketUserRecordMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.request.CustomizeRequestWrapper;
import com.ticketflow.service.delaysend.DelayOperateProgramDataSend;
import com.ticketflow.service.properties.OrderProperties;
import com.ticketflow.util.ServiceLockTool;
import com.ticketflow.vo.*;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.ticketflow.constant.Constant.ALIPAY_NOTIFY_FAILURE_RESULT;
import static com.ticketflow.constant.Constant.ALIPAY_NOTIFY_SUCCESS_RESULT;
import static com.ticketflow.constant.Constant.WX_NOTIFY_FAILURE_RESULT;
import static com.ticketflow.constant.Constant.WX_NOTIFY_SUCCESS_RESULT;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OrderService 核心订单生命周期测试：
 * 创建（doCreate/create/createByMq）、支付（pay/getPayDto）、支付检查（payCheck）、
 * 取消（cancel/initiateCancel）、状态流转（updateOrderRelatedData/checkOrderStatus）、
 * 座位缓存操作（updateProgramRelatedDataResolution）、异步回调（alipayNotify/wxNotify）、
 * MQ 创建（createMq）、查询（get/selectList/simpleList/accountOrderCount/getCache）。
 *
 * 说明：
 * 1. 自引用 orderService 字段注入 mock，用于验证 payCheck/alipayNotify/wxNotify 中
 *    orderService.updateOrderRelatedData 的调用；cancel/initiateCancel 走真实链路。
 * 2. SpringUtil 静态容器需 @BeforeAll 初始化（createRedisKey 依赖前缀）。
 */
class OrderServiceTest {

    private OrderService orderService;
    private OrderService orderServiceMock;
    private UidGenerator uidGenerator;
    private OrderMapper orderMapper;
    private OrderTicketUserMapper orderTicketUserMapper;
    private OrderTicketUserService orderTicketUserService;
    private OrderTicketUserRecordService orderTicketUserRecordService;
    private OrderProgramCacheResolutionOperate orderProgramCacheResolutionOperate;
    private RedisCache redisCache;
    private PayClient payClient;
    private UserClient userClient;
    private OrderProperties orderProperties;
    private ServiceLockTool serviceLockTool;
    private ProgramClient programClient;
    private OrderTicketUserRecordMapper orderTicketUserRecordMapper;
    private OrderProgramMapper orderProgramMapper;
    private DelayOperateProgramDataSend delayOperateProgramDataSend;
    private RLock lock;

    private static final Long ORDER_NUMBER = 1001L;
    private static final Long USER_ID = 1L;
    private static final Long PROGRAM_ID = 10L;
    private static final Long IDENTIFIER_ID = 99L;
    private static final Long TICKET_CATEGORY_ID = 200L;
    private static final Long SEAT_ID = 3000L;
    private static final Long TICKET_USER_ID = 4000L;

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
        orderService = new OrderService();
        orderServiceMock = mock(OrderService.class);
        uidGenerator = mock(UidGenerator.class);
        orderMapper = mock(OrderMapper.class);
        orderTicketUserMapper = mock(OrderTicketUserMapper.class);
        orderTicketUserService = mock(OrderTicketUserService.class);
        orderTicketUserRecordService = mock(OrderTicketUserRecordService.class);
        orderProgramCacheResolutionOperate = mock(OrderProgramCacheResolutionOperate.class);
        redisCache = mock(RedisCache.class);
        payClient = mock(PayClient.class);
        userClient = mock(UserClient.class);
        orderProperties = new OrderProperties();
        ReflectionTestUtils.setField(orderProperties, "orderPayNotifyUrl", "http://pay/order/alipay/notify");
        ReflectionTestUtils.setField(orderProperties, "wxPayNotifyUrl", "http://pay/order/wx/notify");
        ReflectionTestUtils.setField(orderProperties, "orderPayReturnUrl", "http://front/paySuccess");
        serviceLockTool = mock(ServiceLockTool.class);
        programClient = mock(ProgramClient.class);
        orderTicketUserRecordMapper = mock(OrderTicketUserRecordMapper.class);
        orderProgramMapper = mock(OrderProgramMapper.class);
        delayOperateProgramDataSend = mock(DelayOperateProgramDataSend.class);
        lock = mock(RLock.class);
        when(serviceLockTool.getLock(any(), anyString(), any())).thenReturn(lock);

        ReflectionTestUtils.setField(orderService, "uidGenerator", uidGenerator);
        ReflectionTestUtils.setField(orderService, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(orderService, "orderTicketUserMapper", orderTicketUserMapper);
        ReflectionTestUtils.setField(orderService, "orderTicketUserService", orderTicketUserService);
        ReflectionTestUtils.setField(orderService, "orderTicketUserRecordService", orderTicketUserRecordService);
        ReflectionTestUtils.setField(orderService, "orderProgramCacheResolutionOperate", orderProgramCacheResolutionOperate);
        ReflectionTestUtils.setField(orderService, "redisCache", redisCache);
        ReflectionTestUtils.setField(orderService, "payClient", payClient);
        ReflectionTestUtils.setField(orderService, "userClient", userClient);
        ReflectionTestUtils.setField(orderService, "orderProperties", orderProperties);
        ReflectionTestUtils.setField(orderService, "orderService", orderServiceMock);
        ReflectionTestUtils.setField(orderService, "serviceLockTool", serviceLockTool);
        ReflectionTestUtils.setField(orderService, "programClient", programClient);
        ReflectionTestUtils.setField(orderService, "orderTicketUserRecordMapper", orderTicketUserRecordMapper);
        ReflectionTestUtils.setField(orderService, "orderProgramMapper", orderProgramMapper);
        ReflectionTestUtils.setField(orderService, "delayOperateProgramDataSend", delayOperateProgramDataSend);
    }

    // ==================== 创建 doCreate/create/createByMq ====================

    private OrderCreateDomain buildCreateDomain() {
        OrderCreateDomain domain = new OrderCreateDomain();
        domain.setIdentifierId(IDENTIFIER_ID);
        domain.setOrderNumber(ORDER_NUMBER);
        domain.setProgramId(PROGRAM_ID);
        domain.setProgramItemPicture("pic");
        domain.setUserId(USER_ID);
        domain.setProgramTitle("节目");
        domain.setProgramPlace("场馆");
        domain.setOrderPrice(new BigDecimal("100.00"));
        domain.setOrderVersion(ProgramOrderVersion.V4_VERSION.getValue());
        OrderTicketUserCreateDto ticketUserCreateDto = new OrderTicketUserCreateDto();
        ticketUserCreateDto.setSeatId(SEAT_ID);
        ticketUserCreateDto.setTicketCategoryId(TICKET_CATEGORY_ID);
        ticketUserCreateDto.setTicketUserId(TICKET_USER_ID);
        ticketUserCreateDto.setOrderPrice(new BigDecimal("100.00"));
        domain.setOrderTicketUserCreateDtoList(List.of(ticketUserCreateDto));
        return domain;
    }

    @Test
    void doCreate订单已存在时抛ORDER_EXIST() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(new Order());
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.doCreate(buildCreateDomain()));
        assertEquals(BaseCode.ORDER_EXIST.getCode(), e.getCode());
    }

    @Test
    void doCreate正常创建时插入四张表并累加订单数量() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(uidGenerator.getUid()).thenReturn(1L, 2L, 3L);
        when(redisCache.incrBy(any(), anyLong())).thenReturn(1L);
        String orderNumber = orderService.doCreate(buildCreateDomain());
        assertEquals(String.valueOf(ORDER_NUMBER), orderNumber);

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(orderCaptor.capture());
        assertEquals(ORDER_NUMBER, orderCaptor.getValue().getOrderNumber());
        assertEquals(PROGRAM_ID, orderCaptor.getValue().getProgramId());
        assertEquals("电子票", orderCaptor.getValue().getDistributionMode());

        ArgumentCaptor<List<OrderTicketUser>> ticketUserCaptor = ArgumentCaptor.forClass(List.class);
        verify(orderTicketUserService).saveBatch(ticketUserCaptor.capture());
        assertEquals(1, ticketUserCaptor.getValue().size());
        assertEquals(SEAT_ID, ticketUserCaptor.getValue().get(0).getSeatId());

        ArgumentCaptor<List<OrderTicketUserRecord>> recordCaptor = ArgumentCaptor.forClass(List.class);
        verify(orderTicketUserRecordService).saveBatch(recordCaptor.capture());
        assertEquals(1, recordCaptor.getValue().size());
        assertEquals(RecordType.REDUCE.getCode(), recordCaptor.getValue().get(0).getRecordTypeCode());
        assertEquals(IDENTIFIER_ID, recordCaptor.getValue().get(0).getIdentifierId());

        ArgumentCaptor<OrderProgram> orderProgramCaptor = ArgumentCaptor.forClass(OrderProgram.class);
        verify(orderProgramMapper).insert(orderProgramCaptor.capture());
        assertEquals(ORDER_NUMBER, orderProgramCaptor.getValue().getOrderNumber());

        verify(redisCache).incrBy(eq(RedisKeyBuild.createRedisKey(RedisKeyManage.ACCOUNT_ORDER_COUNT, USER_ID, PROGRAM_ID)), eq(1L));
    }

    @Test
    void doCreate_同步器激活时计数延迟到事务提交后() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(uidGenerator.getUid()).thenReturn(1L, 2L, 3L);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.initSynchronization();
        try {
            String orderNumber = orderService.doCreate(buildCreateDomain());
            assertEquals(String.valueOf(ORDER_NUMBER), orderNumber);
            // 同步器激活：incrBy 不应立即执行，而是注册到 afterCommit 回调（事务提交后）
            verify(redisCache, never()).incrBy(any(), anyLong());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void create_无事务上下文_计数立即累加() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(uidGenerator.getUid()).thenReturn(1L, 2L, 3L);
        when(redisCache.incrBy(any(), anyLong())).thenReturn(1L);
        OrderCreateDto dto = new OrderCreateDto();
        BeanUtils.copyProperties(buildCreateDomain(), dto);
        String orderNumber = orderService.create(dto);
        assertEquals(String.valueOf(ORDER_NUMBER), orderNumber);
        // 无事务上下文（纯 Mockito 无 Spring 代理）：走回退分支立即累加，等价原行为
        verify(redisCache).incrBy(eq(RedisKeyBuild.createRedisKey(RedisKeyManage.ACCOUNT_ORDER_COUNT, USER_ID, PROGRAM_ID)), eq(1L));
    }

    // ==================== 支付 pay ====================

    private Order buildOrder(Integer orderStatus) {
        Order order = new Order();
        order.setId(1L);
        order.setOrderNumber(ORDER_NUMBER);
        order.setUserId(USER_ID);
        order.setProgramId(PROGRAM_ID);
        order.setIdentifierId(IDENTIFIER_ID);
        order.setOrderPrice(new BigDecimal("100.00"));
        order.setOrderStatus(orderStatus);
        order.setOrderVersion(ProgramOrderVersion.V4_VERSION.getValue());
        return order;
    }

    private OrderPayDto buildPayDto(String channel) {
        OrderPayDto orderPayDto = new OrderPayDto();
        orderPayDto.setOrderNumber(ORDER_NUMBER);
        orderPayDto.setChannel(channel);
        orderPayDto.setPrice(new BigDecimal("100.00"));
        return orderPayDto;
    }

    @Test
    void pay订单不存在时抛ORDER_NOT_EXIST() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.pay(buildPayDto(PayChannel.WX.getValue())));
        assertEquals(BaseCode.ORDER_NOT_EXIST.getCode(), e.getCode());
    }

    @Test
    void pay订单已取消时抛ORDER_CANCEL() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.CANCEL.getCode()));
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.pay(buildPayDto(PayChannel.WX.getValue())));
        assertEquals(BaseCode.ORDER_CANCEL.getCode(), e.getCode());
    }

    @Test
    void pay订单已支付时抛ORDER_PAY() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.PAY.getCode()));
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.pay(buildPayDto(PayChannel.WX.getValue())));
        assertEquals(BaseCode.ORDER_PAY.getCode(), e.getCode());
    }

    @Test
    void pay订单已退款时抛ORDER_REFUND() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.REFUND.getCode()));
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.pay(buildPayDto(PayChannel.WX.getValue())));
        assertEquals(BaseCode.ORDER_REFUND.getCode(), e.getCode());
    }

    @Test
    void pay金额与订单金额不一致时抛PAY_PRICE_NOT_EQUAL_ORDER_PRICE() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.NO_PAY.getCode()));
        OrderPayDto orderPayDto = buildPayDto(PayChannel.WX.getValue());
        orderPayDto.setPrice(new BigDecimal("99.00"));
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.pay(orderPayDto));
        assertEquals(BaseCode.PAY_PRICE_NOT_EQUAL_ORDER_PRICE.getCode(), e.getCode());
    }

    @Test
    void pay微信渠道时使用微信回调地址并返回支付跳转地址() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.NO_PAY.getCode()));
        when(payClient.commonPay(any(PayDto.class))).thenReturn(ApiResponse.ok("https://wx/pay"));
        String result = orderService.pay(buildPayDto(PayChannel.WX.getValue()));
        assertEquals("https://wx/pay", result);
        ArgumentCaptor<PayDto> payDtoCaptor = ArgumentCaptor.forClass(PayDto.class);
        verify(payClient).commonPay(payDtoCaptor.capture());
        assertEquals(orderProperties.getWxPayNotifyUrl(), payDtoCaptor.getValue().getNotifyUrl());
        assertEquals(String.valueOf(ORDER_NUMBER), payDtoCaptor.getValue().getOrderNumber());
    }

    @Test
    void pay非微信渠道时使用支付宝回调地址() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.NO_PAY.getCode()));
        when(payClient.commonPay(any(PayDto.class))).thenReturn(ApiResponse.ok("https://alipay/pay"));
        orderService.pay(buildPayDto(PayChannel.ALIPAY.getValue()));
        ArgumentCaptor<PayDto> payDtoCaptor = ArgumentCaptor.forClass(PayDto.class);
        verify(payClient).commonPay(payDtoCaptor.capture());
        assertEquals(orderProperties.getOrderPayNotifyUrl(), payDtoCaptor.getValue().getNotifyUrl());
    }

    @Test
    void pay支付服务返回失败时抛异常携带支付服务响应码() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.NO_PAY.getCode()));
        ApiResponse<String> failResponse = ApiResponse.error(9999, "支付失败");
        when(payClient.commonPay(any(PayDto.class))).thenReturn(failResponse);
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.pay(buildPayDto(PayChannel.WX.getValue())));
        assertEquals(9999, e.getCode());
    }

    // ==================== 支付检查 payCheck ====================

    private OrderPayCheckDto buildPayCheckDto(Integer payChannelType) {
        OrderPayCheckDto orderPayCheckDto = new OrderPayCheckDto();
        orderPayCheckDto.setOrderNumber(ORDER_NUMBER);
        orderPayCheckDto.setPayChannelType(payChannelType);
        return orderPayCheckDto;
    }

    @Test
    void payCheck订单不存在时抛ORDER_NOT_EXIST() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.payCheck(buildPayCheckDto(PayChannel.ALIPAY.getCode())));
        assertEquals(BaseCode.ORDER_NOT_EXIST.getCode(), e.getCode());
    }

    @Test
    void payCheck取消订单且支付渠道非法时抛PAY_CHANNEL_NOT_EXIST() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.CANCEL.getCode()));
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.payCheck(buildPayCheckDto(999)));
        assertEquals(BaseCode.PAY_CHANNEL_NOT_EXIST.getCode(), e.getCode());
    }

    @Test
    void payCheck取消订单且退款成功时更新订单为退款状态() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.CANCEL.getCode()));
        when(payClient.refund(any(RefundDto.class))).thenReturn(ApiResponse.ok("refund"));
        OrderPayCheckVo vo = orderService.payCheck(buildPayCheckDto(PayChannel.ALIPAY.getCode()));
        assertEquals(OrderStatus.REFUND.getCode(), vo.getOrderStatus());
        assertNotNull(vo.getCancelOrderTime());
        ArgumentCaptor<RefundDto> refundCaptor = ArgumentCaptor.forClass(RefundDto.class);
        verify(payClient).refund(refundCaptor.capture());
        assertEquals(PayChannel.ALIPAY.getValue(), refundCaptor.getValue().getChannel());
        assertEquals(String.valueOf(ORDER_NUMBER), refundCaptor.getValue().getOrderNumber());
        verify(orderMapper).update(any(Order.class), any(Wrapper.class));
    }

    @Test
    void payCheck取消订单且退款失败时视图保持取消状态且未更新数据库() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.CANCEL.getCode()));
        when(payClient.refund(any(RefundDto.class))).thenReturn(ApiResponse.error(500, "退款失败"));
        OrderPayCheckVo vo = orderService.payCheck(buildPayCheckDto(PayChannel.ALIPAY.getCode()));
        assertEquals(OrderStatus.CANCEL.getCode(), vo.getOrderStatus());
        verify(orderMapper, never()).update(any(Order.class), any(Wrapper.class));
    }

    @Test
    void payCheck对账服务返回失败时抛异常() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.NO_PAY.getCode()));
        when(payClient.tradeCheck(any(TradeCheckDto.class))).thenReturn(ApiResponse.error(8888, "对账失败"));
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.payCheck(buildPayCheckDto(PayChannel.ALIPAY.getCode())));
        assertEquals(8888, e.getCode());
    }

    @Test
    void payCheck对账结果为空时抛PAY_BILL_NOT_EXIST() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.NO_PAY.getCode()));
        when(payClient.tradeCheck(any(TradeCheckDto.class))).thenReturn(ApiResponse.ok(null));
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.payCheck(buildPayCheckDto(PayChannel.ALIPAY.getCode())));
        assertEquals(BaseCode.PAY_BILL_NOT_EXIST.getCode(), e.getCode());
    }

    @Test
    void payCheck对账未成功时抛PAY_TRADE_CHECK_ERROR() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.NO_PAY.getCode()));
        TradeCheckVo tradeCheckVo = new TradeCheckVo();
        tradeCheckVo.setSuccess(false);
        when(payClient.tradeCheck(any(TradeCheckDto.class))).thenReturn(ApiResponse.ok(tradeCheckVo));
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.payCheck(buildPayCheckDto(PayChannel.ALIPAY.getCode())));
        assertEquals(BaseCode.PAY_TRADE_CHECK_ERROR.getCode(), e.getCode());
    }

    @Test
    void payCheck账单已支付且本地状态未支付时调用状态流转更新() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.NO_PAY.getCode()));
        TradeCheckVo tradeCheckVo = new TradeCheckVo();
        tradeCheckVo.setSuccess(true);
        tradeCheckVo.setPayBillStatus(PayBillStatus.PAY.getCode());
        when(payClient.tradeCheck(any(TradeCheckDto.class))).thenReturn(ApiResponse.ok(tradeCheckVo));
        OrderPayCheckVo vo = orderService.payCheck(buildPayCheckDto(PayChannel.ALIPAY.getCode()));
        assertEquals(PayBillStatus.PAY.getCode(), vo.getOrderStatus());
        assertNotNull(vo.getPayOrderTime());
        verify(orderServiceMock).updateOrderRelatedData(ORDER_NUMBER, OrderStatus.PAY);
    }

    @Test
    void payCheck账单状态与本地一致时不触发状态流转() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.PAY.getCode()));
        TradeCheckVo tradeCheckVo = new TradeCheckVo();
        tradeCheckVo.setSuccess(true);
        tradeCheckVo.setPayBillStatus(PayBillStatus.PAY.getCode());
        when(payClient.tradeCheck(any(TradeCheckDto.class))).thenReturn(ApiResponse.ok(tradeCheckVo));
        orderService.payCheck(buildPayCheckDto(PayChannel.ALIPAY.getCode()));
        verify(orderServiceMock, never()).updateOrderRelatedData(any(), any());
    }

    // ==================== 状态流转 updateOrderRelatedData ====================

    private List<OrderTicketUser> buildOrderTicketUserList() {
        OrderTicketUser orderTicketUser = new OrderTicketUser();
        orderTicketUser.setId(1L);
        orderTicketUser.setOrderNumber(ORDER_NUMBER);
        orderTicketUser.setTicketCategoryId(TICKET_CATEGORY_ID);
        orderTicketUser.setSeatId(SEAT_ID);
        orderTicketUser.setTicketUserId(TICKET_USER_ID);
        orderTicketUser.setOrderPrice(new BigDecimal("100.00"));
        orderTicketUser.setOrderStatus(OrderStatus.NO_PAY.getCode());
        return List.of(orderTicketUser);
    }

    private void stubUpdateOrderRelatedDataCommon(Integer orderStatus) {
        stubUpdateOrderRelatedDataCommon(orderStatus, ProgramOrderVersion.V4_VERSION.getValue());
    }

    private void stubUpdateOrderRelatedDataCommon(Integer orderStatus, Integer orderVersion) {
        Order order = buildOrder(orderStatus);
        order.setOrderVersion(orderVersion);
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(orderTicketUserMapper.selectList(any(Wrapper.class))).thenReturn(buildOrderTicketUserList());
        when(uidGenerator.getUid()).thenReturn(9L);
        when(orderMapper.update(any(Order.class), any(Wrapper.class))).thenReturn(1);
        when(orderTicketUserMapper.update(any(OrderTicketUser.class), any(Wrapper.class))).thenReturn(1);
        when(redisCache.multiGetForHash(any(), anyList(), any())).thenReturn(List.of(new SeatVo()));
        when(redisCache.incrBy(any(), anyLong())).thenReturn(1L);
    }

    @Test
    void updateOrderRelatedData非法状态时抛OPERATE_ORDER_STATUS_NOT_PERMIT() {
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.updateOrderRelatedData(ORDER_NUMBER, OrderStatus.NO_PAY));
        assertEquals(BaseCode.OPERATE_ORDER_STATUS_NOT_PERMIT.getCode(), e.getCode());
    }

    @Test
    void updateOrderRelatedData订单不存在时抛ORDER_NOT_EXIST() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.updateOrderRelatedData(ORDER_NUMBER, OrderStatus.CANCEL));
        assertEquals(BaseCode.ORDER_NOT_EXIST.getCode(), e.getCode());
    }

    @Test
    void updateOrderRelatedData订单已取消时抛ORDER_CANCEL() {
        stubUpdateOrderRelatedDataCommon(OrderStatus.CANCEL.getCode());
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.updateOrderRelatedData(ORDER_NUMBER, OrderStatus.PAY));
        assertEquals(BaseCode.ORDER_CANCEL.getCode(), e.getCode());
    }

    @Test
    void updateOrderRelatedData订单已支付时抛ORDER_PAY() {
        stubUpdateOrderRelatedDataCommon(OrderStatus.PAY.getCode());
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.updateOrderRelatedData(ORDER_NUMBER, OrderStatus.CANCEL));
        assertEquals(BaseCode.ORDER_PAY.getCode(), e.getCode());
    }

    @Test
    void updateOrderRelatedData订单已退款时抛ORDER_REFUND() {
        stubUpdateOrderRelatedDataCommon(OrderStatus.REFUND.getCode());
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.updateOrderRelatedData(ORDER_NUMBER, OrderStatus.CANCEL));
        assertEquals(BaseCode.ORDER_REFUND.getCode(), e.getCode());
    }

    @Test
    void updateOrderRelatedData购票人订单为空时抛TICKET_USER_ORDER_NOT_EXIST() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.NO_PAY.getCode()));
        when(orderTicketUserMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.updateOrderRelatedData(ORDER_NUMBER, OrderStatus.CANCEL));
        assertEquals(BaseCode.TICKET_USER_ORDER_NOT_EXIST.getCode(), e.getCode());
    }

    @Test
    void updateOrderRelatedData取消时写入增加记录并扣减账户订单数() {
        stubUpdateOrderRelatedDataCommon(OrderStatus.NO_PAY.getCode(), ProgramOrderVersion.V3_VERSION.getValue());
        orderService.updateOrderRelatedData(ORDER_NUMBER, OrderStatus.CANCEL);

        ArgumentCaptor<Order> updateOrderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).update(updateOrderCaptor.capture(), any(Wrapper.class));
        assertEquals(OrderStatus.CANCEL.getCode(), updateOrderCaptor.getValue().getOrderStatus());
        assertNotNull(updateOrderCaptor.getValue().getCancelOrderTime());

        ArgumentCaptor<List<OrderTicketUserRecord>> recordCaptor = ArgumentCaptor.forClass(List.class);
        verify(orderTicketUserRecordService).saveBatch(recordCaptor.capture());
        OrderTicketUserRecord record = recordCaptor.getValue().get(0);
        assertEquals(RecordType.INCREASE.getCode(), record.getRecordTypeCode());
        assertEquals(IDENTIFIER_ID, record.getIdentifierId());
        assertEquals(TICKET_USER_ID, record.getTicketUserId());

        verify(redisCache).incrBy(eq(RedisKeyBuild.createRedisKey(RedisKeyManage.ACCOUNT_ORDER_COUNT, USER_ID, PROGRAM_ID)), eq(-1L));
        verify(orderProgramCacheResolutionOperate).programCacheReverseOperate(anyList(), any(Object[].class));
        verify(programClient, never()).operateProgramData(any());
    }

    @Test
    void updateOrderRelatedData支付时写入变更记录并调用V4节目服务() {
        stubUpdateOrderRelatedDataCommon(OrderStatus.NO_PAY.getCode());
        when(programClient.operateProgramData(any(ProgramOperateDataDto.class))).thenReturn(ApiResponse.ok(true));
        orderService.updateOrderRelatedData(ORDER_NUMBER, OrderStatus.PAY);

        ArgumentCaptor<Order> updateOrderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).update(updateOrderCaptor.capture(), any(Wrapper.class));
        assertEquals(OrderStatus.PAY.getCode(), updateOrderCaptor.getValue().getOrderStatus());
        assertNotNull(updateOrderCaptor.getValue().getPayOrderTime());

        ArgumentCaptor<List<OrderTicketUserRecord>> recordCaptor = ArgumentCaptor.forClass(List.class);
        verify(orderTicketUserRecordService).saveBatch(recordCaptor.capture());
        assertEquals(RecordType.CHANGE_STATUS.getCode(), recordCaptor.getValue().get(0).getRecordTypeCode());

        verify(redisCache, never()).incrBy(eq(RedisKeyBuild.createRedisKey(RedisKeyManage.ACCOUNT_ORDER_COUNT, USER_ID, PROGRAM_ID)), anyLong());
        ArgumentCaptor<ProgramOperateDataDto> operateCaptor = ArgumentCaptor.forClass(ProgramOperateDataDto.class);
        verify(programClient).operateProgramData(operateCaptor.capture());
        assertEquals(PROGRAM_ID, operateCaptor.getValue().getProgramId());
        assertEquals(SellStatus.SOLD.getCode(), operateCaptor.getValue().getSellStatus());
        verify(orderProgramCacheResolutionOperate).programCacheReverseOperate(anyList(), any(Object[].class));
    }

    @Test
    void updateOrderRelatedData支付时V4节目服务返回失败不抛异常且Lua仍执行() {
        stubUpdateOrderRelatedDataCommon(OrderStatus.NO_PAY.getCode());
        when(programClient.operateProgramData(any(ProgramOperateDataDto.class)))
                .thenReturn(ApiResponse.error(500, "program服务不可用"));

        // 事务外尽力而为：Feign 失败不阻断订单状态流转，Lua（Redis 权威）照常执行
        assertDoesNotThrow(() -> orderService.updateOrderRelatedData(ORDER_NUMBER, OrderStatus.PAY));

        ArgumentCaptor<Order> updateOrderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).update(updateOrderCaptor.capture(), any(Wrapper.class));
        assertEquals(OrderStatus.PAY.getCode(), updateOrderCaptor.getValue().getOrderStatus());
        verify(orderProgramCacheResolutionOperate).programCacheReverseOperate(anyList(), any(Object[].class));
    }

    @Test
    void updateOrderRelatedData支付时V4节目服务抛异常不抛异常且Lua仍执行() {
        stubUpdateOrderRelatedDataCommon(OrderStatus.NO_PAY.getCode());
        when(programClient.operateProgramData(any(ProgramOperateDataDto.class)))
                .thenThrow(new RuntimeException("program服务异常"));

        assertDoesNotThrow(() -> orderService.updateOrderRelatedData(ORDER_NUMBER, OrderStatus.PAY));

        verify(orderMapper).update(any(Order.class), any(Wrapper.class));
        verify(orderProgramCacheResolutionOperate).programCacheReverseOperate(anyList(), any(Object[].class));
    }

    @Test
    void updateOrderRelatedData同步器激活时数据同步延迟到事务提交后() {
        stubUpdateOrderRelatedDataCommon(OrderStatus.NO_PAY.getCode());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.initSynchronization();
        try {
            orderService.updateOrderRelatedData(ORDER_NUMBER, OrderStatus.CANCEL);
            // 同步器激活：incrBy/Lua 不应立即执行，而是注册到 afterCommit 回调（事务提交后）
            verify(redisCache, never()).incrBy(any(), anyLong());
            verify(orderProgramCacheResolutionOperate, never()).programCacheReverseOperate(anyList(), any(Object[].class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void updateOrderRelatedData更新主订单失败时抛ORDER_CANAL_ERROR() {
        stubUpdateOrderRelatedDataCommon(OrderStatus.NO_PAY.getCode());
        when(orderMapper.update(any(Order.class), any(Wrapper.class))).thenReturn(0);
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.updateOrderRelatedData(ORDER_NUMBER, OrderStatus.CANCEL));
        assertEquals(BaseCode.ORDER_CANAL_ERROR.getCode(), e.getCode());
    }

    @Test
    void updateOrderRelatedData更新购票人订单失败时抛ORDER_CANAL_ERROR() {
        stubUpdateOrderRelatedDataCommon(OrderStatus.NO_PAY.getCode());
        when(orderTicketUserMapper.update(any(OrderTicketUser.class), any(Wrapper.class))).thenReturn(0);
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.updateOrderRelatedData(ORDER_NUMBER, OrderStatus.CANCEL));
        assertEquals(BaseCode.ORDER_CANAL_ERROR.getCode(), e.getCode());
    }

    // ==================== checkOrderStatus ====================

    @Test
    void checkOrderStatus空订单抛ORDER_NOT_EXIST() {
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class, () -> orderService.checkOrderStatus(null));
        assertEquals(BaseCode.ORDER_NOT_EXIST.getCode(), e.getCode());
    }

    @Test
    void checkOrderStatus已取消订单抛ORDER_CANCEL() {
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.checkOrderStatus(buildOrder(OrderStatus.CANCEL.getCode())));
        assertEquals(BaseCode.ORDER_CANCEL.getCode(), e.getCode());
    }

    @Test
    void checkOrderStatus已支付订单抛ORDER_PAY() {
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.checkOrderStatus(buildOrder(OrderStatus.PAY.getCode())));
        assertEquals(BaseCode.ORDER_PAY.getCode(), e.getCode());
    }

    @Test
    void checkOrderStatus已退款订单抛ORDER_REFUND() {
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.checkOrderStatus(buildOrder(OrderStatus.REFUND.getCode())));
        assertEquals(BaseCode.ORDER_REFUND.getCode(), e.getCode());
    }

    @Test
    void checkOrderStatus未支付订单通过校验() {
        assertDoesNotThrow(() -> orderService.checkOrderStatus(buildOrder(OrderStatus.NO_PAY.getCode())));
    }

    // ==================== 座位缓存操作 updateProgramRelatedDataResolution ====================

    @Test
    void updateProgramRelatedDataResolution座位集合为空时抛LOCK_SEAT_LIST_EMPTY() {
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.updateProgramRelatedDataResolution(PROGRAM_ID, new java.util.HashMap<>(),
                        OrderStatus.CANCEL, IDENTIFIER_ID, USER_ID, List.of(), ProgramOrderVersion.V3_VERSION.getValue()));
        assertEquals(BaseCode.LOCK_SEAT_LIST_EMPTY.getCode(), e.getCode());
    }

    @Test
    void updateProgramRelatedDataResolutionV3支付时走缓存逆向操作并延迟发送() {
        SeatVo seatVo = new SeatVo();
        seatVo.setId(SEAT_ID);
        when(redisCache.multiGetForHash(any(), anyList(), any())).thenReturn(List.of(seatVo));
        java.util.Map<Long, List<Long>> seatMap = java.util.Map.of(TICKET_CATEGORY_ID, List.of(SEAT_ID));
        orderService.updateProgramRelatedDataResolution(PROGRAM_ID, seatMap, OrderStatus.PAY,
                IDENTIFIER_ID, USER_ID, List.of(), ProgramOrderVersion.V3_VERSION.getValue());
        verify(orderProgramCacheResolutionOperate).programCacheReverseOperate(anyList(), any(Object[].class));
        verify(delayOperateProgramDataSend).sendMessage(anyString());
        verify(programClient, never()).operateProgramData(any());
    }

    @Test
    void updateProgramRelatedDataResolutionV4支付时走Feign更新节目数据() {
        SeatVo seatVo = new SeatVo();
        seatVo.setId(SEAT_ID);
        when(redisCache.multiGetForHash(any(), anyList(), any())).thenReturn(List.of(seatVo));
        when(programClient.operateProgramData(any(ProgramOperateDataDto.class))).thenReturn(ApiResponse.ok(true));
        java.util.Map<Long, List<Long>> seatMap = java.util.Map.of(TICKET_CATEGORY_ID, List.of(SEAT_ID));
        orderService.updateProgramRelatedDataResolution(PROGRAM_ID, seatMap, OrderStatus.PAY,
                IDENTIFIER_ID, USER_ID, List.of(), ProgramOrderVersion.V4_VERSION.getValue());
        verify(programClient).operateProgramData(any(ProgramOperateDataDto.class));
        verify(orderProgramCacheResolutionOperate).programCacheReverseOperate(anyList(), any(Object[].class));
        verify(delayOperateProgramDataSend, never()).sendMessage(anyString());
    }

    @Test
    void updateProgramRelatedDataResolutionV4取消时座位标记未售卖() {
        SeatVo seatVo = new SeatVo();
        seatVo.setId(SEAT_ID);
        when(redisCache.multiGetForHash(any(), anyList(), any())).thenReturn(List.of(seatVo));
        when(programClient.operateProgramData(any(ProgramOperateDataDto.class))).thenReturn(ApiResponse.ok(true));
        java.util.Map<Long, List<Long>> seatMap = java.util.Map.of(TICKET_CATEGORY_ID, List.of(SEAT_ID));
        orderService.updateProgramRelatedDataResolution(PROGRAM_ID, seatMap, OrderStatus.CANCEL,
                IDENTIFIER_ID, USER_ID, List.of(), ProgramOrderVersion.V4_VERSION.getValue());
        ArgumentCaptor<ProgramOperateDataDto> operateCaptor = ArgumentCaptor.forClass(ProgramOperateDataDto.class);
        verify(programClient).operateProgramData(operateCaptor.capture());
        assertEquals(SellStatus.NO_SOLD.getCode(), operateCaptor.getValue().getSellStatus());
    }

    @Test
    void updateProgramRelatedDataResolutionV4节目服务失败时不抛异常且Lua仍执行() {
        SeatVo seatVo = new SeatVo();
        seatVo.setId(SEAT_ID);
        when(redisCache.multiGetForHash(any(), anyList(), any())).thenReturn(List.of(seatVo));
        when(programClient.operateProgramData(any(ProgramOperateDataDto.class))).thenReturn(ApiResponse.error(7777, "失败"));
        java.util.Map<Long, List<Long>> seatMap = java.util.Map.of(TICKET_CATEGORY_ID, List.of(SEAT_ID));
        // 事务外尽力而为：Feign 失败不抛异常，Lua（Redis 权威）照常执行，DB 侧由投影任务/守恒任务观测兜底
        assertDoesNotThrow(() -> orderService.updateProgramRelatedDataResolution(PROGRAM_ID, seatMap, OrderStatus.CANCEL,
                IDENTIFIER_ID, USER_ID, List.of(), ProgramOrderVersion.V4_VERSION.getValue()));
        verify(orderProgramCacheResolutionOperate).programCacheReverseOperate(anyList(), any(Object[].class));
    }

    // ==================== 丢弃订单座位回滚 rollbackProgramSeatByDiscard ====================

    private OrderCreateMq buildRollbackMq() {
        OrderCreateMq orderCreateMq = new OrderCreateMq();
        orderCreateMq.setIdentifierId(IDENTIFIER_ID);
        orderCreateMq.setOrderNumber(ORDER_NUMBER);
        orderCreateMq.setProgramId(PROGRAM_ID);
        orderCreateMq.setUserId(USER_ID);
        orderCreateMq.setOrderVersion(ProgramOrderVersion.V4_VERSION.getValue());
        OrderTicketUserCreateDto ticketUserCreateDto = new OrderTicketUserCreateDto();
        ticketUserCreateDto.setSeatId(SEAT_ID);
        ticketUserCreateDto.setTicketCategoryId(TICKET_CATEGORY_ID);
        ticketUserCreateDto.setTicketUserId(TICKET_USER_ID);
        orderCreateMq.setOrderTicketUserCreateDtoList(List.of(ticketUserCreateDto));
        return orderCreateMq;
    }

    @Test
    void rollbackProgramSeatByDiscard订单已存在时不回滚座位() {
        when(orderMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        orderService.rollbackProgramSeatByDiscard(buildRollbackMq());
        verify(orderProgramCacheResolutionOperate, never()).programCacheReverseOperate(anyList(), any(Object[].class));
    }

    @Test
    void rollbackProgramSeatByDiscard座位在锁定时释放并恢复余票() {
        when(orderMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        SeatVo seatVo = new SeatVo();
        seatVo.setId(SEAT_ID);
        seatVo.setTicketCategoryId(TICKET_CATEGORY_ID);
        seatVo.setSellStatus(SellStatus.LOCK.getCode());
        when(redisCache.multiGetForHash(any(), anyList(), any())).thenReturn(List.of(seatVo));

        orderService.rollbackProgramSeatByDiscard(buildRollbackMq());

        ArgumentCaptor<List> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> dataCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(orderProgramCacheResolutionOperate).programCacheReverseOperate(keysCaptor.capture(), dataCaptor.capture());
        List<String> keys = keysCaptor.getValue();
        assertEquals(String.valueOf(OrderStatus.CANCEL.getCode()), keys.get(0));
        assertEquals(String.valueOf(PROGRAM_ID), keys.get(1));
        assertEquals(RecordType.INCREASE.getValue(), keys.get(4));

        Object[] data = dataCaptor.getValue();
        JSONArray unLockSeatJsonArray = JSON.parseArray(String.valueOf(data[0]));
        assertEquals(SEAT_ID, unLockSeatJsonArray.getJSONObject(0).getJSONArray("unLockSeatIdList").getLong(0));

        JSONArray addSeatDataJsonArray = JSON.parseArray(String.valueOf(data[1]));
        JSONObject seatDataList = addSeatDataJsonArray.getJSONObject(0);
        assertTrue(seatDataList.getString("seatHashKeyAdd").contains("no_sold"));
        JSONObject addSeatJson = JSON.parseObject(seatDataList.getJSONArray("seatDataList").getString(1));
        assertEquals(SellStatus.NO_SOLD.getCode(), addSeatJson.getInteger("sellStatus"));

        JSONArray ticketCategoryJsonArray = JSON.parseArray(String.valueOf(data[2]));
        assertEquals(TICKET_CATEGORY_ID, ticketCategoryJsonArray.getJSONObject(0).getLong("ticketCategoryId"));
        assertEquals(1, ticketCategoryJsonArray.getJSONObject(0).getInteger("count"));

        JSONArray seatUserJsonArray = JSON.parseArray(String.valueOf(data[3]));
        assertEquals(SEAT_ID, seatUserJsonArray.getJSONObject(0).getLong("seatId"));
        assertEquals(TICKET_USER_ID, seatUserJsonArray.getJSONObject(0).getLong("ticketUserId"));
    }

    @Test
    void rollbackProgramSeatByDiscard座位不在锁定时跳过回滚() {
        when(orderMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(redisCache.multiGetForHash(any(), anyList(), any())).thenReturn(List.of());
        orderService.rollbackProgramSeatByDiscard(buildRollbackMq());
        verify(orderProgramCacheResolutionOperate, never()).programCacheReverseOperate(anyList(), any(Object[].class));
    }

    // ==================== 取消 initiateCancel ====================

    private OrderCancelDto buildCancelDto() {
        OrderCancelDto orderCancelDto = new OrderCancelDto();
        orderCancelDto.setOrderNumber(ORDER_NUMBER);
        return orderCancelDto;
    }

    @Test
    void initiateCancel订单不存在时抛ORDER_NOT_EXIST() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.initiateCancel(buildCancelDto()));
        assertEquals(BaseCode.ORDER_NOT_EXIST.getCode(), e.getCode());
    }

    @Test
    void initiateCancel订单非未支付状态时抛CAN_NOT_CANCEL() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.PAY.getCode()));
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.initiateCancel(buildCancelDto()));
        assertEquals(BaseCode.CAN_NOT_CANCEL.getCode(), e.getCode());
    }

    @Test
    void initiateCancel未支付订单走真实取消链路() {
        Order order = buildOrder(OrderStatus.NO_PAY.getCode());
        order.setOrderVersion(ProgramOrderVersion.V3_VERSION.getValue());
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(orderTicketUserMapper.selectList(any(Wrapper.class))).thenReturn(buildOrderTicketUserList());
        when(uidGenerator.getUid()).thenReturn(9L);
        when(orderMapper.update(any(Order.class), any(Wrapper.class))).thenReturn(1);
        when(orderTicketUserMapper.update(any(OrderTicketUser.class), any(Wrapper.class))).thenReturn(1);
        when(redisCache.multiGetForHash(any(), anyList(), any())).thenReturn(List.of(new SeatVo()));
        when(redisCache.incrBy(any(), anyLong())).thenReturn(1L);
        assertTrue(orderService.initiateCancel(buildCancelDto()));
        verify(orderMapper).update(any(Order.class), any(Wrapper.class));
    }

    // ==================== 支付宝回调 alipayNotify ====================

    private HttpServletRequest buildAlipayRequest(String body) {
        CustomizeRequestWrapper request = mock(CustomizeRequestWrapper.class);
        when(request.getRequestBody()).thenReturn(body);
        return request;
    }

    @Test
    void alipayNotify缺少订单号时返回failure() {
        assertEquals(ALIPAY_NOTIFY_FAILURE_RESULT, orderService.alipayNotify(buildAlipayRequest("trade_no=xxx")));
    }

    @Test
    void alipayNotify订单号格式非法时返回failure且不加锁() {
        String result = orderService.alipayNotify(buildAlipayRequest("out_trade_no=not-a-number"));
        assertEquals(ALIPAY_NOTIFY_FAILURE_RESULT, result);
        verify(serviceLockTool, never()).getLock(any(), anyString(), any());
    }

    @Test
    void alipayNotify取消订单对账数据为空时不退款返回failure() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.CANCEL.getCode()));
        when(payClient.notify(any(NotifyDto.class))).thenReturn(ApiResponse.ok(null));
        String result = orderService.alipayNotify(buildAlipayRequest("out_trade_no=" + ORDER_NUMBER));
        assertEquals(ALIPAY_NOTIFY_FAILURE_RESULT, result);
        verify(payClient, never()).refund(any(RefundDto.class));
    }

    @Test
    void alipayNotify未取消订单对账数据为空时返回failure() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.NO_PAY.getCode()));
        when(payClient.notify(any(NotifyDto.class))).thenReturn(ApiResponse.ok(null));
        String result = orderService.alipayNotify(buildAlipayRequest("out_trade_no=" + ORDER_NUMBER));
        assertEquals(ALIPAY_NOTIFY_FAILURE_RESULT, result);
        verify(orderServiceMock, never()).updateOrderRelatedData(any(), any());
    }

    @Test
    void alipayNotify订单不存在时抛ORDER_NOT_EXIST() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.alipayNotify(buildAlipayRequest("out_trade_no=" + ORDER_NUMBER)));
        assertEquals(BaseCode.ORDER_NOT_EXIST.getCode(), e.getCode());
    }

    @Test
    void alipayNotify取消订单对账成功且退款成功时更新为退款并返回success() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.CANCEL.getCode()));
        NotifyVo notifyVo = new NotifyVo();
        notifyVo.setPayResult(ALIPAY_NOTIFY_SUCCESS_RESULT);
        when(payClient.notify(any(NotifyDto.class))).thenReturn(ApiResponse.ok(notifyVo));
        when(payClient.refund(any(RefundDto.class))).thenReturn(ApiResponse.ok("refund"));
        String result = orderService.alipayNotify(buildAlipayRequest("out_trade_no=" + ORDER_NUMBER));
        assertEquals(ALIPAY_NOTIFY_SUCCESS_RESULT, result);
        verify(payClient).refund(any(RefundDto.class));
        verify(orderMapper).update(any(Order.class), any(Wrapper.class));
    }

    @Test
    void alipayNotify取消订单退款失败时返回failure() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.CANCEL.getCode()));
        NotifyVo notifyVo = new NotifyVo();
        notifyVo.setPayResult(ALIPAY_NOTIFY_SUCCESS_RESULT);
        when(payClient.notify(any(NotifyDto.class))).thenReturn(ApiResponse.ok(notifyVo));
        when(payClient.refund(any(RefundDto.class))).thenReturn(ApiResponse.error(500, "退款失败"));
        String result = orderService.alipayNotify(buildAlipayRequest("out_trade_no=" + ORDER_NUMBER));
        assertEquals(ALIPAY_NOTIFY_FAILURE_RESULT, result);
        verify(orderMapper, never()).update(any(Order.class), any(Wrapper.class));
    }

    @Test
    void alipayNotify取消订单对账失败时暂不退款返回failure() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.CANCEL.getCode()));
        when(payClient.notify(any(NotifyDto.class))).thenReturn(ApiResponse.error(10000, "验签失败"));
        String result = orderService.alipayNotify(buildAlipayRequest("out_trade_no=" + ORDER_NUMBER));
        assertEquals(ALIPAY_NOTIFY_FAILURE_RESULT, result);
        verify(payClient, never()).refund(any(RefundDto.class));
    }

    @Test
    void alipayNotify取消订单对账成功但支付未确认时返回failure() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.CANCEL.getCode()));
        NotifyVo notifyVo = new NotifyVo();
        notifyVo.setPayResult("wait");
        when(payClient.notify(any(NotifyDto.class))).thenReturn(ApiResponse.ok(notifyVo));
        String result = orderService.alipayNotify(buildAlipayRequest("out_trade_no=" + ORDER_NUMBER));
        assertEquals(ALIPAY_NOTIFY_FAILURE_RESULT, result);
        verify(payClient, never()).refund(any(RefundDto.class));
    }

    @Test
    void alipayNotify未取消订单对账成功且支付成功时触发状态流转() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.NO_PAY.getCode()));
        NotifyVo notifyVo = new NotifyVo();
        notifyVo.setPayResult(ALIPAY_NOTIFY_SUCCESS_RESULT);
        notifyVo.setOutTradeNo(String.valueOf(ORDER_NUMBER));
        when(payClient.notify(any(NotifyDto.class))).thenReturn(ApiResponse.ok(notifyVo));
        String result = orderService.alipayNotify(buildAlipayRequest("out_trade_no=" + ORDER_NUMBER));
        assertEquals(ALIPAY_NOTIFY_SUCCESS_RESULT, result);
        verify(orderServiceMock).updateOrderRelatedData(ORDER_NUMBER, OrderStatus.PAY);
    }

    @Test
    void alipayNotify对账失败时抛异常() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.NO_PAY.getCode()));
        when(payClient.notify(any(NotifyDto.class))).thenReturn(ApiResponse.error(10000, "验签失败"));
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.alipayNotify(buildAlipayRequest("out_trade_no=" + ORDER_NUMBER)));
        assertEquals(10000, e.getCode());
    }

    @Test
    void alipayNotify未取消订单对账成功但支付未成功时返回支付结果() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.NO_PAY.getCode()));
        NotifyVo notifyVo = new NotifyVo();
        notifyVo.setPayResult("wait");
        when(payClient.notify(any(NotifyDto.class))).thenReturn(ApiResponse.ok(notifyVo));
        String result = orderService.alipayNotify(buildAlipayRequest("out_trade_no=" + ORDER_NUMBER));
        assertEquals("wait", result);
        verify(orderServiceMock, never()).updateOrderRelatedData(any(), any());
    }

    // ==================== 微信回调 wxNotify ====================

    private HttpServletRequest buildWxRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(anyString())).thenReturn("header-value");
        return request;
    }

    @Test
    void wxNotify对账服务失败时返回FAIL() {
        when(payClient.notify(any(NotifyDto.class))).thenReturn(ApiResponse.error(10000, "验签失败"));
        String result = orderService.wxNotify(buildWxRequest());
        assertEquals(WX_NOTIFY_FAILURE_RESULT, result);
        verify(serviceLockTool, never()).getLock(any(), anyString(), any());
    }

    @Test
    void wxNotify对账数据为空时返回FAIL() {
        when(payClient.notify(any(NotifyDto.class))).thenReturn(ApiResponse.ok(null));
        String result = orderService.wxNotify(buildWxRequest());
        assertEquals(WX_NOTIFY_FAILURE_RESULT, result);
        verify(serviceLockTool, never()).getLock(any(), anyString(), any());
    }

    @Test
    void wxNotify支付结果非成功时返回FAIL() {
        NotifyVo notifyVo = new NotifyVo();
        notifyVo.setPayResult("NOTPAY");
        when(payClient.notify(any(NotifyDto.class))).thenReturn(ApiResponse.ok(notifyVo));
        String result = orderService.wxNotify(buildWxRequest());
        assertEquals(WX_NOTIFY_FAILURE_RESULT, result);
    }

    @Test
    void wxNotify订单号格式非法时返回FAIL() {
        NotifyVo notifyVo = new NotifyVo();
        notifyVo.setPayResult(WX_NOTIFY_SUCCESS_RESULT);
        notifyVo.setOutTradeNo("not-a-number");
        when(payClient.notify(any(NotifyDto.class))).thenReturn(ApiResponse.ok(notifyVo));
        String result = orderService.wxNotify(buildWxRequest());
        assertEquals(WX_NOTIFY_FAILURE_RESULT, result);
    }

    @Test
    void wxNotify订单不存在时抛ORDER_NOT_EXIST() {
        NotifyVo notifyVo = new NotifyVo();
        notifyVo.setPayResult(WX_NOTIFY_SUCCESS_RESULT);
        notifyVo.setOutTradeNo(String.valueOf(ORDER_NUMBER));
        when(payClient.notify(any(NotifyDto.class))).thenReturn(ApiResponse.ok(notifyVo));
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.wxNotify(buildWxRequest()));
        assertEquals(BaseCode.ORDER_NOT_EXIST.getCode(), e.getCode());
    }

    @Test
    void wxNotify取消订单退款成功时更新为退款并返回SUCCESS() {
        NotifyVo notifyVo = new NotifyVo();
        notifyVo.setPayResult(WX_NOTIFY_SUCCESS_RESULT);
        notifyVo.setOutTradeNo(String.valueOf(ORDER_NUMBER));
        when(payClient.notify(any(NotifyDto.class))).thenReturn(ApiResponse.ok(notifyVo));
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.CANCEL.getCode()));
        when(payClient.refund(any(RefundDto.class))).thenReturn(ApiResponse.ok("refund"));
        String result = orderService.wxNotify(buildWxRequest());
        assertEquals(WX_NOTIFY_SUCCESS_RESULT, result);
        verify(payClient).refund(any(RefundDto.class));
        verify(orderMapper).update(any(Order.class), any(Wrapper.class));
        verify(orderServiceMock, never()).updateOrderRelatedData(any(), any());
    }

    @Test
    void wxNotify取消订单退款失败时返回FAIL() {
        NotifyVo notifyVo = new NotifyVo();
        notifyVo.setPayResult(WX_NOTIFY_SUCCESS_RESULT);
        notifyVo.setOutTradeNo(String.valueOf(ORDER_NUMBER));
        when(payClient.notify(any(NotifyDto.class))).thenReturn(ApiResponse.ok(notifyVo));
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.CANCEL.getCode()));
        when(payClient.refund(any(RefundDto.class))).thenReturn(ApiResponse.error(500, "退款失败"));
        String result = orderService.wxNotify(buildWxRequest());
        assertEquals(WX_NOTIFY_FAILURE_RESULT, result);
        verify(orderMapper, never()).update(any(Order.class), any(Wrapper.class));
    }

    @Test
    void wxNotify未取消订单对账成功时触发支付状态流转并返回SUCCESS() {
        NotifyVo notifyVo = new NotifyVo();
        notifyVo.setPayResult(WX_NOTIFY_SUCCESS_RESULT);
        notifyVo.setOutTradeNo(String.valueOf(ORDER_NUMBER));
        when(payClient.notify(any(NotifyDto.class))).thenReturn(ApiResponse.ok(notifyVo));
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.NO_PAY.getCode()));
        String result = orderService.wxNotify(buildWxRequest());
        assertEquals(WX_NOTIFY_SUCCESS_RESULT, result);
        verify(orderServiceMock).updateOrderRelatedData(ORDER_NUMBER, OrderStatus.PAY);
    }

    @Test
    void wxNotify请求头与原始请求体透传给支付服务() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(Constant.WX_SIGNATURE_HEADER)).thenReturn("sig");
        when(request.getHeader(Constant.WX_SERIAL_HEADER)).thenReturn("serial");
        when(request.getHeader(Constant.WX_NONCE_HEADER)).thenReturn("nonce");
        when(request.getHeader(Constant.WX_TIMESTAMP_HEADER)).thenReturn("ts");
        NotifyVo notifyVo = new NotifyVo();
        notifyVo.setPayResult(WX_NOTIFY_SUCCESS_RESULT);
        notifyVo.setOutTradeNo(String.valueOf(ORDER_NUMBER));
        when(payClient.notify(any(NotifyDto.class))).thenReturn(ApiResponse.ok(notifyVo));
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.NO_PAY.getCode()));
        orderService.wxNotify(request);
        ArgumentCaptor<NotifyDto> notifyCaptor = ArgumentCaptor.forClass(NotifyDto.class);
        verify(payClient).notify(notifyCaptor.capture());
        assertEquals(PayChannel.WX.getValue(), notifyCaptor.getValue().getChannel());
        assertEquals("sig", notifyCaptor.getValue().getParams().get(Constant.WX_SIGNATURE_HEADER));
    }

    // ==================== MQ 创建 createMq ====================

    private OrderCreateMq buildCreateMq() {
        OrderCreateMq orderCreateMq = new OrderCreateMq();
        orderCreateMq.setIdentifierId(IDENTIFIER_ID);
        orderCreateMq.setOrderNumber(ORDER_NUMBER);
        orderCreateMq.setProgramId(PROGRAM_ID);
        orderCreateMq.setUserId(USER_ID);
        orderCreateMq.setOrderPrice(new BigDecimal("100.00"));
        orderCreateMq.setOrderVersion(ProgramOrderVersion.V4_VERSION.getValue());
        OrderTicketUserCreateDto ticketUserCreateDto = new OrderTicketUserCreateDto();
        ticketUserCreateDto.setSeatId(SEAT_ID);
        ticketUserCreateDto.setTicketCategoryId(TICKET_CATEGORY_ID);
        ticketUserCreateDto.setTicketUserId(TICKET_USER_ID);
        ticketUserCreateDto.setOrderPrice(new BigDecimal("100.00"));
        orderCreateMq.setOrderTicketUserCreateDtoList(List.of(ticketUserCreateDto));
        return orderCreateMq;
    }

    @Test
    void createMq节目服务锁定失败时抛异常且不写丢弃记录() {
        when(programClient.operateSeatLockAndTicketCategoryRemainNumber(any(ReduceRemainNumberDto.class)))
                .thenReturn(ApiResponse.error(7777, "锁定失败"));
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.createMq(buildCreateMq()));
        assertEquals(7777, e.getCode());
        // 丢弃记录统一由 CreateOrderConsumer 外层 catch 写入，createMq 内不再重复写入
        verify(redisCache, never()).leftPushForList(any(RedisKeyBuild.class), any());
        verify(programClient, never()).operateProgramData(any());
    }

    @Test
    void createMq建单失败时反向恢复DB扣减并抛出原异常() {
        when(programClient.operateSeatLockAndTicketCategoryRemainNumber(any(ReduceRemainNumberDto.class)))
                .thenReturn(ApiResponse.ok(true));
        when(orderMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        doThrow(new RuntimeException("建单失败")).when(orderMapper).insert(any(Order.class));
        when(programClient.operateProgramData(any(ProgramOperateDataDto.class))).thenReturn(ApiResponse.ok(true));

        RuntimeException e = assertThrows(RuntimeException.class, () -> orderService.createMq(buildCreateMq()));
        assertEquals("建单失败", e.getMessage());

        ArgumentCaptor<ProgramOperateDataDto> captor = ArgumentCaptor.forClass(ProgramOperateDataDto.class);
        verify(programClient).operateProgramData(captor.capture());
        assertEquals(SellStatus.NO_SOLD.getCode(), captor.getValue().getSellStatus());
        assertEquals(List.of(SEAT_ID), captor.getValue().getSeatIdList());
        assertEquals(ProgramOrderVersion.V4_VERSION.getValue(), captor.getValue().getOrderVersion());
        assertEquals(TICKET_CATEGORY_ID, captor.getValue().getTicketCategoryCountDtoList().get(0).getTicketCategoryId());
    }

    @Test
    void createMq建单失败且订单已存在时跳过反向恢复避免释放已建订单座位() {
        when(programClient.operateSeatLockAndTicketCategoryRemainNumber(any(ReduceRemainNumberDto.class)))
                .thenReturn(ApiResponse.ok(true));
        when(orderMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        doThrow(new RuntimeException("建单失败")).when(orderMapper).insert(any(Order.class));

        RuntimeException e = assertThrows(RuntimeException.class, () -> orderService.createMq(buildCreateMq()));
        assertEquals("建单失败", e.getMessage());
        // 订单已存在（消息重放等），不执行反向恢复，避免释放已建订单的座位导致超卖
        verify(programClient, never()).operateProgramData(any(ProgramOperateDataDto.class));
    }

    @Test
    void createMq建单失败且反向恢复返回失败时不掩盖原异常() {
        when(programClient.operateSeatLockAndTicketCategoryRemainNumber(any(ReduceRemainNumberDto.class)))
                .thenReturn(ApiResponse.ok(true));
        when(orderMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        doThrow(new RuntimeException("建单失败")).when(orderMapper).insert(any(Order.class));
        when(programClient.operateProgramData(any(ProgramOperateDataDto.class)))
                .thenReturn(ApiResponse.error(8888, "反向恢复失败"));

        RuntimeException e = assertThrows(RuntimeException.class, () -> orderService.createMq(buildCreateMq()));
        assertEquals("建单失败", e.getMessage());
        verify(programClient).operateProgramData(any(ProgramOperateDataDto.class));
    }

    @Test
    void createMq建单失败且反向恢复抛异常时不掩盖原异常() {
        when(programClient.operateSeatLockAndTicketCategoryRemainNumber(any(ReduceRemainNumberDto.class)))
                .thenReturn(ApiResponse.ok(true));
        when(orderMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        doThrow(new RuntimeException("建单失败")).when(orderMapper).insert(any(Order.class));
        doThrow(new RuntimeException("反向异常")).when(programClient).operateProgramData(any(ProgramOperateDataDto.class));

        RuntimeException e = assertThrows(RuntimeException.class, () -> orderService.createMq(buildCreateMq()));
        assertEquals("建单失败", e.getMessage());
    }

    @Test
    void createMq锁定成功时创建订单并写入MQ订单缓存() {
        when(programClient.operateSeatLockAndTicketCategoryRemainNumber(any(ReduceRemainNumberDto.class)))
                .thenReturn(ApiResponse.ok(true));
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(uidGenerator.getUid()).thenReturn(1L, 2L, 3L);
        when(redisCache.incrBy(any(), anyLong())).thenReturn(1L);
        String orderNumber = orderService.createMq(buildCreateMq());
        assertEquals(String.valueOf(ORDER_NUMBER), orderNumber);
        verify(orderMapper).insert(any(Order.class));
        verify(redisCache).set(eq(RedisKeyBuild.createRedisKey(RedisKeyManage.ORDER_MQ, ORDER_NUMBER)),
                eq(String.valueOf(ORDER_NUMBER)), eq(10L), eq(java.util.concurrent.TimeUnit.MINUTES));
    }

    @Test
    void createMq重复消息_幂等成功返回原订单号() {
        // 第一次消费：无既有订单，正常建单
        // 第二次消费（同 orderNumber 重放）：doCreate 防重 selectOne 命中 → ORDER_EXIST
        //  → createMq 特判为幂等成功返回原订单号，不写 DISCARD_ORDER
        Order existOrder = new Order();
        existOrder.setOrderNumber(ORDER_NUMBER);
        when(programClient.operateSeatLockAndTicketCategoryRemainNumber(any(ReduceRemainNumberDto.class)))
                .thenReturn(ApiResponse.ok(true));
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null, existOrder);
        when(orderMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(uidGenerator.getUid()).thenReturn(1L, 2L, 3L);
        when(redisCache.incrBy(any(), anyLong())).thenReturn(1L);

        String first = orderService.createMq(buildCreateMq());
        assertEquals(String.valueOf(ORDER_NUMBER), first);

        // 重复消息：不抛异常，幂等返回相同订单号
        String second = orderService.createMq(buildCreateMq());
        assertEquals(String.valueOf(ORDER_NUMBER), second);
        // createMq 内不写 DISCARD_ORDER（真实失败才由 CreateOrderConsumer 外层写入）
        verify(redisCache, never()).leftPushForList(any(RedisKeyBuild.class), any());
    }

    @Test
    void createMq重复消息_幂等成功不触发反向恢复() {
        Order existOrder = new Order();
        existOrder.setOrderNumber(ORDER_NUMBER);
        when(programClient.operateSeatLockAndTicketCategoryRemainNumber(any(ReduceRemainNumberDto.class)))
                .thenReturn(ApiResponse.ok(true));
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(existOrder);
        when(orderMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        String orderNumber = orderService.createMq(buildCreateMq());

        assertEquals(String.valueOf(ORDER_NUMBER), orderNumber);
        // 幂等重复不执行反向恢复（不回滚已建订单的 DB 座位/余票）
        verify(programClient, never()).operateProgramData(any(ProgramOperateDataDto.class));
    }

    // ==================== 查询 ====================

    @Test
    void get订单不存在时抛ORDER_NOT_EXIST() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        OrderGetDto orderGetDto = new OrderGetDto();
        orderGetDto.setOrderNumber(ORDER_NUMBER);
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class, () -> orderService.get(orderGetDto));
        assertEquals(BaseCode.ORDER_NOT_EXIST.getCode(), e.getCode());
    }

    @Test
    void get购票人订单为空时抛TICKET_USER_ORDER_NOT_EXIST() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.PAY.getCode()));
        when(orderTicketUserMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());
        OrderGetDto orderGetDto = new OrderGetDto();
        orderGetDto.setOrderNumber(ORDER_NUMBER);
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class, () -> orderService.get(orderGetDto));
        assertEquals(BaseCode.TICKET_USER_ORDER_NOT_EXIST.getCode(), e.getCode());
    }

    @Test
    void get正常时组装座位信息与用户购票人信息() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.PAY.getCode()));
        OrderTicketUser ticketUser1 = new OrderTicketUser();
        ticketUser1.setOrderNumber(ORDER_NUMBER);
        ticketUser1.setTicketCategoryId(TICKET_CATEGORY_ID);
        ticketUser1.setSeatId(SEAT_ID);
        ticketUser1.setTicketUserId(TICKET_USER_ID);
        ticketUser1.setOrderPrice(new BigDecimal("100.00"));
        ticketUser1.setSeatInfo("A排1座");
        OrderTicketUser ticketUser2 = new OrderTicketUser();
        ticketUser2.setOrderNumber(ORDER_NUMBER);
        ticketUser2.setTicketCategoryId(TICKET_CATEGORY_ID);
        ticketUser2.setSeatId(SEAT_ID + 1);
        ticketUser2.setTicketUserId(TICKET_USER_ID + 1);
        ticketUser2.setOrderPrice(new BigDecimal("100.00"));
        ticketUser2.setSeatInfo("A排2座");
        when(orderTicketUserMapper.selectList(any(Wrapper.class))).thenReturn(List.of(ticketUser1, ticketUser2));

        UserGetAndTicketUserListVo userVo = new UserGetAndTicketUserListVo();
        UserVo userVoInfo = new UserVo();
        userVoInfo.setId(USER_ID);
        userVo.setUserVo(userVoInfo);
        TicketUserVo ticketUserVo1 = new TicketUserVo();
        ticketUserVo1.setId(TICKET_USER_ID);
        TicketUserVo ticketUserVo2 = new TicketUserVo();
        ticketUserVo2.setId(TICKET_USER_ID + 1);
        userVo.setTicketUserVoList(List.of(ticketUserVo1, ticketUserVo2));
        when(userClient.getUserAndTicketUserList(any(UserGetAndTicketUserListDto.class))).thenReturn(ApiResponse.ok(userVo));

        OrderGetDto orderGetDto = new OrderGetDto();
        orderGetDto.setOrderNumber(ORDER_NUMBER);
        OrderGetVo vo = orderService.get(orderGetDto);
        assertNotNull(vo.getOrderTicketInfoVoList());
        assertEquals(1, vo.getOrderTicketInfoVoList().size());
        OrderTicketInfoVo infoVo = vo.getOrderTicketInfoVoList().get(0);
        assertEquals("A排1座,A排2座", infoVo.getSeatInfo());
        assertEquals(2, infoVo.getQuantity());
        assertEquals(new BigDecimal("200.00"), infoVo.getRelPrice());
        assertNotNull(vo.getUserAndTicketUserInfoVo());
        assertEquals(USER_ID, vo.getUserAndTicketUserInfoVo().getUserInfoVo().getId());
        assertEquals(2, vo.getUserAndTicketUserInfoVo().getTicketUserInfoVoList().size());
    }

    @Test
    void get用户服务返回失败时抛异常() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.PAY.getCode()));
        when(orderTicketUserMapper.selectList(any(Wrapper.class))).thenReturn(buildOrderTicketUserList());
        when(userClient.getUserAndTicketUserList(any(UserGetAndTicketUserListDto.class))).thenReturn(ApiResponse.error(5000, "用户服务失败"));
        OrderGetDto orderGetDto = new OrderGetDto();
        orderGetDto.setOrderNumber(ORDER_NUMBER);
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class, () -> orderService.get(orderGetDto));
        assertEquals(5000, e.getCode());
    }

    @Test
    void get用户服务数据为空时抛RPC_RESULT_DATA_EMPTY() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.PAY.getCode()));
        when(orderTicketUserMapper.selectList(any(Wrapper.class))).thenReturn(buildOrderTicketUserList());
        when(userClient.getUserAndTicketUserList(any(UserGetAndTicketUserListDto.class))).thenReturn(ApiResponse.ok(null));
        OrderGetDto orderGetDto = new OrderGetDto();
        orderGetDto.setOrderNumber(ORDER_NUMBER);
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class, () -> orderService.get(orderGetDto));
        assertEquals(BaseCode.RPC_RESULT_DATA_EMPTY.getCode(), e.getCode());
    }

    @Test
    void get购票人缺失时过滤空条目正常返回() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(buildOrder(OrderStatus.PAY.getCode()));
        OrderTicketUser ticketUser1 = new OrderTicketUser();
        ticketUser1.setOrderNumber(ORDER_NUMBER);
        ticketUser1.setTicketCategoryId(TICKET_CATEGORY_ID);
        ticketUser1.setSeatId(SEAT_ID);
        ticketUser1.setTicketUserId(TICKET_USER_ID);
        ticketUser1.setOrderPrice(new BigDecimal("100.00"));
        OrderTicketUser ticketUser2 = new OrderTicketUser();
        ticketUser2.setOrderNumber(ORDER_NUMBER);
        ticketUser2.setTicketCategoryId(TICKET_CATEGORY_ID);
        ticketUser2.setSeatId(SEAT_ID + 1);
        ticketUser2.setTicketUserId(TICKET_USER_ID + 1);
        ticketUser2.setOrderPrice(new BigDecimal("100.00"));
        when(orderTicketUserMapper.selectList(any(Wrapper.class))).thenReturn(List.of(ticketUser1, ticketUser2));

        UserGetAndTicketUserListVo userVo = new UserGetAndTicketUserListVo();
        UserVo userVoInfo = new UserVo();
        userVoInfo.setId(USER_ID);
        userVo.setUserVo(userVoInfo);
        TicketUserVo ticketUserVo1 = new TicketUserVo();
        ticketUserVo1.setId(TICKET_USER_ID);
        userVo.setTicketUserVoList(List.of(ticketUserVo1));
        when(userClient.getUserAndTicketUserList(any(UserGetAndTicketUserListDto.class))).thenReturn(ApiResponse.ok(userVo));

        OrderGetDto orderGetDto = new OrderGetDto();
        orderGetDto.setOrderNumber(ORDER_NUMBER);
        OrderGetVo vo = orderService.get(orderGetDto);
        assertNotNull(vo.getUserAndTicketUserInfoVo());
        assertEquals(1, vo.getUserAndTicketUserInfoVo().getTicketUserInfoVoList().size());
    }

    @Test
    void selectList订单列表为空时返回空列表且不查询聚合数据() {
        OrderListDto orderListDto = new OrderListDto();
        orderListDto.setUserId(USER_ID);
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());
        List<OrderListVo> result = orderService.selectList(orderListDto);
        assertTrue(result.isEmpty());
        verify(orderTicketUserMapper, never()).selectOrderTicketUserAggregate(anyList());
    }

    @Test
    void selectList正常时填充购票人数() {
        OrderListDto orderListDto = new OrderListDto();
        orderListDto.setUserId(USER_ID);
        Order order = buildOrder(OrderStatus.NO_PAY.getCode());
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(order));
        OrderTicketUserAggregate aggregate = new OrderTicketUserAggregate();
        aggregate.setOrderNumber(ORDER_NUMBER);
        aggregate.setOrderTicketUserCount(2);
        when(orderTicketUserMapper.selectOrderTicketUserAggregate(anyList())).thenReturn(List.of(aggregate));
        List<OrderListVo> result = orderService.selectList(orderListDto);
        assertEquals(1, result.size());
        assertEquals(2, result.get(0).getTicketCount());
    }

    @Test
    void simpleList参数都为空时抛USER_ID_AND_ORDER_NUMBER_NOT_EXIST() {
        OrderSimpleListDto orderSimpleListDto = new OrderSimpleListDto();
        TicketFlowFrameException e = assertThrows(TicketFlowFrameException.class,
                () -> orderService.simpleList(orderSimpleListDto));
        assertEquals(BaseCode.USER_ID_AND_ORDER_NUMBER_NOT_EXIST.getCode(), e.getCode());
    }

    @Test
    void simpleList正常查询() {
        OrderSimpleListDto orderSimpleListDto = new OrderSimpleListDto();
        orderSimpleListDto.setOrderNumber(ORDER_NUMBER);
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(buildOrder(OrderStatus.NO_PAY.getCode())));
        List<OrderListVo> result = orderService.simpleList(orderSimpleListDto);
        assertEquals(1, result.size());
        assertEquals(ORDER_NUMBER, result.get(0).getOrderNumber());
    }

    @Test
    void accountOrderCount返回统计数量() {
        AccountOrderCountDto accountOrderCountDto = new AccountOrderCountDto();
        accountOrderCountDto.setUserId(USER_ID);
        accountOrderCountDto.setProgramId(PROGRAM_ID);
        when(orderMapper.accountOrderCount(USER_ID, PROGRAM_ID)).thenReturn(3);
        AccountOrderCountVo vo = orderService.accountOrderCount(accountOrderCountDto);
        assertEquals(3, vo.getCount());
    }

    @Test
    void getCache返回MQ订单缓存值() {
        OrderGetDto orderGetDto = new OrderGetDto();
        orderGetDto.setOrderNumber(ORDER_NUMBER);
        when(redisCache.get(any(RedisKeyBuild.class), eq(String.class))).thenReturn(String.valueOf(ORDER_NUMBER));
        String result = orderService.getCache(orderGetDto);
        assertEquals(String.valueOf(ORDER_NUMBER), result);
        verify(redisCache).get(eq(RedisKeyBuild.createRedisKey(RedisKeyManage.ORDER_MQ, ORDER_NUMBER)), eq(String.class));
    }
}
