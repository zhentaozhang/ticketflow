package com.ticketflow.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ticketflow.client.PayClient;
import com.ticketflow.client.ProgramClient;
import com.ticketflow.client.UserClient;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.domain.DiscardOrder;
import com.ticketflow.domain.OrderCreateDomain;
import com.ticketflow.domain.OrderTraceResult;
import com.ticketflow.domain.PendingOrder;
import com.ticketflow.domain.OrderCreateMq;
import com.ticketflow.domain.SeatIdAndTicketUserIdDomain;
import com.ticketflow.dto.AccountOrderCountDto;
import com.ticketflow.dto.NotifyDto;
import com.ticketflow.dto.OrderCancelDto;
import com.ticketflow.dto.OrderCreateDto;
import com.ticketflow.dto.OrderGetDto;
import com.ticketflow.dto.OrderListDto;
import com.ticketflow.dto.OrderPayCheckDto;
import com.ticketflow.dto.OrderPayDto;
import com.ticketflow.dto.OrderSimpleListDto;
import com.ticketflow.dto.OrderTicketUserCreateDto;
import com.ticketflow.dto.PayDto;
import com.ticketflow.dto.ProgramOperateDataDto;
import com.ticketflow.dto.ReduceRemainNumberDto;
import com.ticketflow.dto.RefundDto;
import com.ticketflow.dto.TicketCategoryCountDto;
import com.ticketflow.dto.TradeCheckDto;
import com.ticketflow.dto.UserGetAndTicketUserListDto;
import com.ticketflow.entity.Order;
import com.ticketflow.entity.OrderProgram;
import com.ticketflow.entity.OrderTicketUser;
import com.ticketflow.entity.OrderTicketUserAggregate;
import com.ticketflow.entity.OrderTicketUserRecord;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.BusinessStatus;
import com.ticketflow.enums.OrderStatus;
import com.ticketflow.enums.PayBillStatus;
import com.ticketflow.enums.PayChannel;
import com.ticketflow.enums.PaymentReconcileResult;
import com.ticketflow.enums.ProgramOrderVersion;
import com.ticketflow.enums.RecordType;
import com.ticketflow.enums.ReconciliationStatus;
import com.ticketflow.enums.SellStatus;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.mapper.OrderMapper;
import com.ticketflow.mapper.OrderProgramMapper;
import com.ticketflow.mapper.OrderTicketUserMapper;
import com.ticketflow.mapper.OrderTicketUserRecordMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.repeatexecutelimit.annotion.RepeatExecuteLimit;
import com.ticketflow.request.CustomizeRequestWrapper;
import com.ticketflow.service.delaysend.DelayOperateProgramDataSend;
import com.ticketflow.service.properties.OrderProperties;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.servicelock.annotion.ServiceLock;
import com.ticketflow.util.DateUtils;
import com.ticketflow.util.ServiceLockTool;
import com.ticketflow.util.StringUtil;
import com.ticketflow.vo.AccountOrderCountVo;
import com.ticketflow.vo.NotifyVo;
import com.ticketflow.vo.OrderGetVo;
import com.ticketflow.vo.OrderListVo;
import com.ticketflow.vo.OrderPayCheckVo;
import com.ticketflow.vo.OrderTicketInfoVo;
import com.ticketflow.vo.SeatVo;
import com.ticketflow.vo.TicketUserInfoVo;
import com.ticketflow.vo.TicketUserVo;
import com.ticketflow.vo.TradeCheckVo;
import com.ticketflow.vo.UserAndTicketUserInfoVo;
import com.ticketflow.vo.UserGetAndTicketUserListVo;
import com.ticketflow.vo.UserInfoVo;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.ticketflow.constant.Constant.ALIPAY_NOTIFY_FAILURE_RESULT;
import static com.ticketflow.constant.Constant.ALIPAY_NOTIFY_SUCCESS_RESULT;
import static com.ticketflow.constant.Constant.GLIDE_LINE;
import static com.ticketflow.constant.Constant.WX_NONCE_HEADER;
import static com.ticketflow.constant.Constant.WX_NOTIFY_FAILURE_RESULT;
import static com.ticketflow.constant.Constant.WX_NOTIFY_SUCCESS_RESULT;
import static com.ticketflow.constant.Constant.WX_RAW_BODY_KEY;
import static com.ticketflow.constant.Constant.WX_SERIAL_HEADER;
import static com.ticketflow.constant.Constant.WX_SIGNATURE_HEADER;
import static com.ticketflow.constant.Constant.WX_TIMESTAMP_HEADER;
import static com.ticketflow.core.DistributedLockConstants.UPDATE_ORDER_STATUS_LOCK;
import static com.ticketflow.core.RepeatExecuteLimitConstants.CANCEL_PROGRAM_ORDER;
import static com.ticketflow.core.RepeatExecuteLimitConstants.CREATE_PROGRAM_ORDER_MQ;

/**
 * 订单服务核心逻辑，覆盖完整订单生命周期：
 * 创建（V2/V3 同步/V4 异步三种策略统一入口）
 * 支付回调（支付宝 notify 签名校验 → 状态流转）
 * 取消（延迟队列超时 → 状态回滚）
 * 对账（补偿记录写回、清理过期 lock 占位）
 * 管理（后台批量关闭、重置调度）
 */
@Slf4j
@Service
public class OrderService extends ServiceImpl<OrderMapper, Order> {

    @Autowired
    private UidGenerator uidGenerator;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderTicketUserMapper orderTicketUserMapper;

    @Autowired
    private OrderTicketUserService orderTicketUserService;

    @Autowired
    private OrderTicketUserRecordService orderTicketUserRecordService;

    @Autowired
    private OrderProgramCacheResolutionOperate orderProgramCacheResolutionOperate;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private PayClient payClient;

    @Autowired
    private UserClient userClient;

    @Autowired
    private OrderProperties orderProperties;

    @Lazy
    @Autowired
    private OrderService orderService;

    @Autowired
    private ServiceLockTool serviceLockTool;

    @Autowired
    private ProgramClient programClient;

    @Autowired
    private OrderTicketUserRecordMapper orderTicketUserRecordMapper;

    @Autowired
    private OrderProgramMapper orderProgramMapper;

    @Autowired
    private DelayOperateProgramDataSend delayOperateProgramDataSend;

    @Transactional(rollbackFor = Exception.class)
    public String create(OrderCreateDto orderCreateDto) {
        OrderCreateDomain orderCreateDomain = new OrderCreateDomain();
        BeanUtils.copyProperties(orderCreateDto, orderCreateDomain);
        return doCreate(orderCreateDomain);
    }

    @Transactional(rollbackFor = Exception.class)
    public String createByMq(OrderCreateMq orderCreateMq) {
        OrderCreateDomain orderCreateDomain = new OrderCreateDomain();
        BeanUtils.copyProperties(orderCreateMq, orderCreateDomain);
        return doCreate(orderCreateDomain);
    }

    @Transactional(rollbackFor = Exception.class)
    public String doCreate(OrderCreateDomain orderCreateDomain) {
        LambdaQueryWrapper<Order> orderLambdaQueryWrapper =
                Wrappers.lambdaQuery(Order.class).eq(Order::getOrderNumber, orderCreateDomain.getOrderNumber());
        //如果订单存在了，那么直接拒绝
        Order oldOrder = orderMapper.selectOne(orderLambdaQueryWrapper);
        if (Objects.nonNull(oldOrder)) {
            throw new TicketFlowFrameException(BaseCode.ORDER_EXIST);
        }
        Order order = new Order();
        BeanUtil.copyProperties(orderCreateDomain, order);
        order.setId(uidGenerator.getUid());
        order.setDistributionMode("电子票");
        order.setTakeTicketMode("请使用购票人身份证直接入场");
        //购票人订单对象
        List<OrderTicketUser> orderTicketUserList = new ArrayList<>();
        //购票人订单记录对象
        List<OrderTicketUserRecord> orderTicketUserRecordList = new ArrayList<>();
        for (OrderTicketUserCreateDto orderTicketUserCreateDto : orderCreateDomain.getOrderTicketUserCreateDtoList()) {
            OrderTicketUser orderTicketUser = new OrderTicketUser();
            BeanUtil.copyProperties(orderTicketUserCreateDto, orderTicketUser);
            orderTicketUser.setId(uidGenerator.getUid());
            orderTicketUserList.add(orderTicketUser);

            OrderTicketUserRecord orderTicketUserRecord = new OrderTicketUserRecord();
            BeanUtil.copyProperties(orderTicketUserCreateDto, orderTicketUserRecord);
            orderTicketUserRecord.setIdentifierId(orderCreateDomain.getIdentifierId());
            orderTicketUserRecord.setTicketUserOrderId(orderTicketUser.getId());
            orderTicketUserRecord.setRecordTypeCode(RecordType.REDUCE.getCode());
            orderTicketUserRecord.setRecordTypeValue(RecordType.REDUCE.getValue());
            orderTicketUserRecordList.add(orderTicketUserRecord);
        }
        //插入主订单
        orderMapper.insert(order);
        //插入购票人订单
        orderTicketUserService.saveBatch(orderTicketUserList);
        //插入购票人订单记录
        orderTicketUserRecordService.saveBatch(orderTicketUserRecordList);
        //插入订单节目
        OrderProgram orderProgram = new OrderProgram();
        orderProgram.setId(uidGenerator.getUid());
        orderProgram.setProgramId(order.getProgramId());
        orderProgram.setOrderNumber(order.getOrderNumber());
        orderProgram.setIdentifierId(order.getIdentifierId());
        orderProgramMapper.insert(orderProgram);
        //用户下此节目的订单数量加1操作
        // V5：ACCOUNT_ORDER_COUNT 由请求侧 V5 Lua 原子维护（INCRBY），消费侧不重复累加
        // V1-V4：请求侧 Lua 不维护计数，仍由消费侧事务提交后累加，保持原语义
        boolean isV5 = Objects.equals(orderCreateDomain.getOrderVersion(), ProgramOrderVersion.V5_VERSION.getValue());
        if (!isV5) {
            // 事务提交后再累加：回滚事务不再产生 Redis 计数漂移；Redis 调用也不占用事务内 DB 连接
            Long increment = (long) orderCreateDomain.getOrderTicketUserCreateDtoList().size();
            Long userId = orderCreateDomain.getUserId();
            Long programId = orderCreateDomain.getProgramId();
            Runnable doIncr = () -> {
                try {
                    redisCache.incrBy(RedisKeyBuild.createRedisKey(RedisKeyManage.ACCOUNT_ORDER_COUNT, userId, programId), increment);
                } catch (Exception e) {
                    log.error("ACCOUNT_ORDER_COUNT 累加失败 userId:{} programId:{}", userId, programId, e);
                }
            };
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                // 事务提交后执行（afterCommit）：回滚事务不累加，Redis 调用移出事务
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        doIncr.run();
                    }
                });
            } else {
                // 无事务上下文（自调用/直测）回退为立即执行，等价原行为
                doIncr.run();
            }
        }
        return String.valueOf(order.getOrderNumber());
    }

    /**
     * 订单取消（幂等入口）。
     *
     * @RepeatExecuteLimit 防止相同 orderNumber 重复执行
     * @ServiceLock(Reentrant) 防止并发取消 + 支付同时进入
     */
    @RepeatExecuteLimit(name = CANCEL_PROGRAM_ORDER, keys = {"#orderCancelDto.orderNumber"})
    @ServiceLock(name = UPDATE_ORDER_STATUS_LOCK, keys = {"#orderCancelDto.orderNumber"})
    @Transactional(rollbackFor = Exception.class)
    public boolean cancel(OrderCancelDto orderCancelDto) {
        updateOrderRelatedData(orderCancelDto.getOrderNumber(), OrderStatus.CANCEL);
        return true;
    }

    public String pay(OrderPayDto orderPayDto) {
        Long orderNumber = orderPayDto.getOrderNumber();
        LambdaQueryWrapper<Order> orderLambdaQueryWrapper =
                Wrappers.lambdaQuery(Order.class).eq(Order::getOrderNumber, orderNumber);
        Order order = orderMapper.selectOne(orderLambdaQueryWrapper);
        if (Objects.isNull(order)) {
            throw new TicketFlowFrameException(BaseCode.ORDER_NOT_EXIST);
        }
        if (Objects.equals(order.getOrderStatus(), OrderStatus.CANCEL.getCode())) {
            throw new TicketFlowFrameException(BaseCode.ORDER_CANCEL);
        }
        if (Objects.equals(order.getOrderStatus(), OrderStatus.PAY.getCode())) {
            throw new TicketFlowFrameException(BaseCode.ORDER_PAY);
        }
        if (Objects.equals(order.getOrderStatus(), OrderStatus.REFUND.getCode())) {
            throw new TicketFlowFrameException(BaseCode.ORDER_REFUND);
        }
        if (orderPayDto.getPrice().compareTo(order.getOrderPrice()) != 0) {
            throw new TicketFlowFrameException(BaseCode.PAY_PRICE_NOT_EQUAL_ORDER_PRICE);
        }
        // 发起支付时把渠道落到订单上，否则事后没有任何地方知道“这一单该找哪个渠道对账”。
        // 这也是支付对账任务能扫出“已取消但钱可能到了”的订单的前提（pay_order_type 之前一直没被写过）。
        PayChannel payChannel = PayChannel.getByValue(orderPayDto.getChannel());
        if (Objects.nonNull(payChannel)) {
            Order updateOrder = new Order();
            updateOrder.setPayOrderType(payChannel.getCode());
            try {
                orderMapper.update(updateOrder, Wrappers.lambdaUpdate(Order.class)
                        .eq(Order::getOrderNumber, orderNumber));
            } catch (Exception e) {
                // 这只是一个用于事后对账的辅助字段，写失败不能阻断支付
                log.error("支付渠道落库失败 orderNumber : {} channel : {}", orderNumber, orderPayDto.getChannel(), e);
            }
        }
        PayDto payDto = getPayDto(orderPayDto, orderNumber);
        ApiResponse<String> payResponse = payClient.commonPay(payDto);
        if (!Objects.equals(payResponse.getCode(), BaseCode.SUCCESS.getCode())) {
            throw new TicketFlowFrameException(payResponse);
        }
        return payResponse.getData();
    }

    private PayDto getPayDto(OrderPayDto orderPayDto, Long orderNumber) {
        PayDto payDto = new PayDto();
        payDto.setOrderNumber(String.valueOf(orderNumber));
        payDto.setPayBillType(orderPayDto.getPayBillType());
        payDto.setSubject(orderPayDto.getSubject());
        payDto.setChannel(orderPayDto.getChannel());
        payDto.setPlatform(orderPayDto.getPlatform());
        payDto.setPrice(orderPayDto.getPrice());
        if (PayChannel.WX.getValue().equals(orderPayDto.getChannel())) {
            payDto.setNotifyUrl(orderProperties.getWxPayNotifyUrl());
        } else {
            payDto.setNotifyUrl(orderProperties.getOrderPayNotifyUrl());
        }
        payDto.setReturnUrl(orderProperties.getOrderPayReturnUrl());
        return payDto;
    }

    /**
     * 支付后订单检查，以订单编号加锁，防止多次更新
     *
     */
    @ServiceLock(name = UPDATE_ORDER_STATUS_LOCK, keys = {"#orderPayCheckDto.orderNumber"})
    public OrderPayCheckVo payCheck(OrderPayCheckDto orderPayCheckDto) {
        OrderPayCheckVo orderPayCheckVo = new OrderPayCheckVo();
        LambdaQueryWrapper<Order> orderLambdaQueryWrapper =
                Wrappers.lambdaQuery(Order.class).eq(Order::getOrderNumber, orderPayCheckDto.getOrderNumber());
        Order order = orderMapper.selectOne(orderLambdaQueryWrapper);
        if (Objects.isNull(order)) {
            throw new TicketFlowFrameException(BaseCode.ORDER_NOT_EXIST);
        }
        BeanUtil.copyProperties(order, orderPayCheckVo);
        if (Objects.equals(order.getOrderStatus(), OrderStatus.CANCEL.getCode())) {
            RefundDto refundDto = new RefundDto();
            refundDto.setOrderNumber(String.valueOf(order.getOrderNumber()));
            refundDto.setAmount(order.getOrderPrice());
            refundDto.setChannel(Optional.ofNullable(PayChannel.getRc(orderPayCheckDto.getPayChannelType()))
                    .map(PayChannel::getValue).orElseThrow(() -> new TicketFlowFrameException(BaseCode.PAY_CHANNEL_NOT_EXIST)));
            refundDto.setReason("延迟订单关闭");
            ApiResponse<String> response = payClient.refund(refundDto);
            if (response.getCode().equals(BaseCode.SUCCESS.getCode())) {
                Order updateOrder = new Order();
                updateOrder.setEditTime(DateUtils.now());
                updateOrder.setOrderStatus(OrderStatus.REFUND.getCode());
                orderMapper.update(updateOrder, Wrappers.lambdaUpdate(Order.class).eq(Order::getOrderNumber, order.getOrderNumber()));
                // 退款成功视图才置退款状态，失败保持数据库的取消状态，下次轮询重试退款
                orderPayCheckVo.setOrderStatus(OrderStatus.REFUND.getCode());
                orderPayCheckVo.setCancelOrderTime(DateUtils.now());
            } else {
                log.error("pay服务退款失败 dto : {} response : {}", JSON.toJSONString(refundDto), JSON.toJSONString(response));
            }
            return orderPayCheckVo;
        }

        TradeCheckDto tradeCheckDto = new TradeCheckDto();
        tradeCheckDto.setOutTradeNo(String.valueOf(orderPayCheckDto.getOrderNumber()));
        tradeCheckDto.setChannel(Optional.ofNullable(PayChannel.getRc(orderPayCheckDto.getPayChannelType()))
                .map(PayChannel::getValue).orElseThrow(() -> new TicketFlowFrameException(BaseCode.PAY_CHANNEL_NOT_EXIST)));
        ApiResponse<TradeCheckVo> tradeCheckVoApiResponse = payClient.tradeCheck(tradeCheckDto);
        if (!Objects.equals(tradeCheckVoApiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
            throw new TicketFlowFrameException(tradeCheckVoApiResponse);
        }
        TradeCheckVo tradeCheckVo = Optional.ofNullable(tradeCheckVoApiResponse.getData())
                .orElseThrow(() -> new TicketFlowFrameException(BaseCode.PAY_BILL_NOT_EXIST));
        if (tradeCheckVo.isSuccess()) {
            Integer payBillStatus = tradeCheckVo.getPayBillStatus();
            Integer orderStatus = order.getOrderStatus();
            if (!Objects.equals(orderStatus, payBillStatus)) {
                orderPayCheckVo.setOrderStatus(payBillStatus);
                try {
                    if (Objects.equals(payBillStatus, PayBillStatus.PAY.getCode())) {
                        orderPayCheckVo.setPayOrderTime(DateUtils.now());
                        orderService.updateOrderRelatedData(order.getOrderNumber(), OrderStatus.PAY);
                    } else if (Objects.equals(payBillStatus, PayBillStatus.CANCEL.getCode())) {
                        orderPayCheckVo.setCancelOrderTime(DateUtils.now());
                        orderService.updateOrderRelatedData(order.getOrderNumber(), OrderStatus.CANCEL);
                    }
                } catch (Exception e) {
                    log.warn("updateOrderRelatedData warn message", e);
                }
            }
        } else {
            throw new TicketFlowFrameException(BaseCode.PAY_TRADE_CHECK_ERROR);
        }
        return orderPayCheckVo;
    }


    /**
     * 支付宝异步通知处理。
     * 手动 ReentrantLock（而非 @ServiceLock）：先加锁（订单号已知），
     * 锁内调 payClient.notify() 验签 + 幂等，再执行退款 / updateOrderRelatedData。
     * 如果订单已取消 → 自动退款（延迟订单关闭场景）。
     * <p>
     * ALIPAY_NOTIFY_SUCCESS_RESULT = "success"（支付宝要求的明文返回）
     */
    public String alipayNotify(HttpServletRequest request) {

        Map<String, String> params = new HashMap<>(256);
        if (request instanceof final CustomizeRequestWrapper customizeRequestWrapper) {
            String requestBody = customizeRequestWrapper.getRequestBody();
            params = StringUtil.convertQueryStringToMap(requestBody);
        }
        log.info("收到支付宝回调通知 params : {}", JSON.toJSONString(params));
        String outTradeNo = params.get("out_trade_no");
        if (StringUtil.isEmpty(outTradeNo)) {
            return "failure";
        }
        // 非法订单号在加锁前校验（对齐 wxNotify），避免锁内抛非业务异常
        long orderNumber;
        try {
            orderNumber = Long.parseLong(outTradeNo);
        } catch (NumberFormatException e) {
            log.error("支付宝回调订单号格式错误 outTradeNo : {}", outTradeNo, e);
            return ALIPAY_NOTIFY_FAILURE_RESULT;
        }

        RLock lock = serviceLockTool.getLock(LockType.Reentrant, UPDATE_ORDER_STATUS_LOCK,
                new String[]{outTradeNo});
        if (!tryLockOrderLock(lock, outTradeNo)) {
            // 拿不到锁就不处理：回调线程不能被无限占用，让支付宝按重试阶梯再来
            return ALIPAY_NOTIFY_FAILURE_RESULT;
        }
        try {
            Order order = orderMapper.selectOne(Wrappers.lambdaQuery(Order.class).eq(Order::getOrderNumber, orderNumber));
            if (Objects.isNull(order)) {
                throw new TicketFlowFrameException(BaseCode.ORDER_NOT_EXIST);
            }
            if (Objects.equals(order.getOrderStatus(), OrderStatus.CANCEL.getCode())) {
                // 先对账再退款：支付宝回调可能早于本地账单状态流转，此时账单仍为 NO_PAY，
                // 直接退款会被拒（PAY_BILL_IS_NOT_PAY_STATUS）。先调 payClient.notify
                // 完成验签+账单状态流转，确认支付成功后才可发起退款
                NotifyDto notifyDto = new NotifyDto();
                notifyDto.setChannel(PayChannel.ALIPAY.getValue());
                notifyDto.setParams(params);
                ApiResponse<NotifyVo> notifyResponse = payClient.notify(notifyDto);
                if (!Objects.equals(notifyResponse.getCode(), BaseCode.SUCCESS.getCode())
                        || Objects.isNull(notifyResponse.getData())
                        || !ALIPAY_NOTIFY_SUCCESS_RESULT.equals(notifyResponse.getData().getPayResult())) {
                    // 对账未确认支付（验签失败/金额不符/状态异常）：按失败应答让支付宝重试
                    log.error("支付宝回调对账失败，暂不退款 dto : {} response : {}",
                            JSON.toJSONString(notifyDto), JSON.toJSONString(notifyResponse));
                    return ALIPAY_NOTIFY_FAILURE_RESULT;
                }
                // 退款失败返回 failure，让支付宝按重试周期继续回调，重试期间再次发起退款
                return refundClosedOrder(outTradeNo, order.getOrderPrice(), PayChannel.ALIPAY.getValue())
                        ? ALIPAY_NOTIFY_SUCCESS_RESULT : ALIPAY_NOTIFY_FAILURE_RESULT;
            }


            NotifyDto notifyDto = new NotifyDto();
            notifyDto.setChannel(PayChannel.ALIPAY.getValue());
            notifyDto.setParams(params);
            ApiResponse<NotifyVo> notifyResponse = payClient.notify(notifyDto);
            if (!Objects.equals(notifyResponse.getCode(), BaseCode.SUCCESS.getCode())) {
                throw new TicketFlowFrameException(notifyResponse);
            }
            if (Objects.isNull(notifyResponse.getData())) {
                log.error("支付宝回调对账返回数据为空 notifyDto : {}", JSON.toJSONString(notifyDto));
                return ALIPAY_NOTIFY_FAILURE_RESULT;
            }
            if (ALIPAY_NOTIFY_SUCCESS_RESULT.equals(notifyResponse.getData().getPayResult())) {
                try {
                    orderService.updateOrderRelatedData(Long.parseLong(notifyResponse.getData().getOutTradeNo())
                            , OrderStatus.PAY);
                } catch (Exception e) {
                    return settlePayCallbackFailure(e, orderNumber, PayChannel.ALIPAY.getValue(),
                            ALIPAY_NOTIFY_SUCCESS_RESULT, ALIPAY_NOTIFY_FAILURE_RESULT);
                }
            }
            return notifyResponse.getData().getPayResult();
        } finally {
            lock.unlock();
        }

    }

    /**
     * 微信支付异步通知处理。
     * 微信回调的 out_trade_no 在 AES-GCM 密文中，无法先取单号再加锁，
     * 因此先调 payClient.notify()（验签+解密+幂等+账单状态流转），拿到单号后再加锁处理订单侧逻辑。
     * 验签失败/业务失败返回 FAIL 让微信重试（幂等保护不会重复入账）。
     */
    public String wxNotify(HttpServletRequest request) {

        String rawBody = "";
        if (request instanceof final CustomizeRequestWrapper customizeRequestWrapper) {
            rawBody = customizeRequestWrapper.getRequestBody();
        }
        log.info("收到微信支付回调通知 rawBody : {}", rawBody);

        Map<String, String> params = new HashMap<>(16);
        params.put(WX_RAW_BODY_KEY, rawBody);
        params.put(WX_SIGNATURE_HEADER, request.getHeader(WX_SIGNATURE_HEADER));
        params.put(WX_SERIAL_HEADER, request.getHeader(WX_SERIAL_HEADER));
        params.put(WX_NONCE_HEADER, request.getHeader(WX_NONCE_HEADER));
        params.put(WX_TIMESTAMP_HEADER, request.getHeader(WX_TIMESTAMP_HEADER));

        NotifyDto notifyDto = new NotifyDto();
        notifyDto.setChannel(PayChannel.WX.getValue());
        notifyDto.setParams(params);
        ApiResponse<NotifyVo> notifyResponse = payClient.notify(notifyDto);
        if (!Objects.equals(notifyResponse.getCode(), BaseCode.SUCCESS.getCode())) {
            log.error("pay服务微信回调处理失败 dto : {} response : {}", JSON.toJSONString(notifyDto), JSON.toJSONString(notifyResponse));
            return WX_NOTIFY_FAILURE_RESULT;
        }
        NotifyVo notifyVo = notifyResponse.getData();
        if (Objects.isNull(notifyVo) || !WX_NOTIFY_SUCCESS_RESULT.equals(notifyVo.getPayResult())) {
            return WX_NOTIFY_FAILURE_RESULT;
        }
        String outTradeNo = notifyVo.getOutTradeNo();

        long orderNumber;
        try {
            orderNumber = Long.parseLong(outTradeNo);
        } catch (NumberFormatException e) {
            log.error("微信回调订单号格式错误 outTradeNo : {}", outTradeNo, e);
            return WX_NOTIFY_FAILURE_RESULT;
        }

        RLock lock = serviceLockTool.getLock(LockType.Reentrant, UPDATE_ORDER_STATUS_LOCK,
                new String[]{outTradeNo});
        if (!tryLockOrderLock(lock, outTradeNo)) {
            // 拿不到锁就不处理：回调线程不能被无限占用，让微信按重试阶梯再来
            return WX_NOTIFY_FAILURE_RESULT;
        }
        try {
            Order order = orderMapper.selectOne(Wrappers.lambdaQuery(Order.class)
                    .eq(Order::getOrderNumber, orderNumber));
            if (Objects.isNull(order)) {
                throw new TicketFlowFrameException(BaseCode.ORDER_NOT_EXIST);
            }
            // 订单已取消：自动退款（延迟订单关闭场景）
            // 退款失败返回 FAIL，微信会重试回调；返回 SUCCESS 会导致退款永久丢失
            if (Objects.equals(order.getOrderStatus(), OrderStatus.CANCEL.getCode())) {
                return refundClosedOrder(outTradeNo, order.getOrderPrice(), PayChannel.WX.getValue())
                        ? WX_NOTIFY_SUCCESS_RESULT : WX_NOTIFY_FAILURE_RESULT;
            }
            try {
                orderService.updateOrderRelatedData(orderNumber, OrderStatus.PAY);
            } catch (Exception e) {
                return settlePayCallbackFailure(e, orderNumber, PayChannel.WX.getValue(),
                        WX_NOTIFY_SUCCESS_RESULT, WX_NOTIFY_FAILURE_RESULT);
            }
            return WX_NOTIFY_SUCCESS_RESULT;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 支付回调获取订单锁的等待上限（秒）。
     * 锁内只有一次本地事务加少量远程调用，正常在毫秒级；超过这个时间说明另一条流程卡住了。
     */
    private static final long ORDER_LOCK_WAIT_SECONDS = 3L;

    /**
     * 支付对账（定时兜底用）：向渠道确认这一笔的真实结果，并只在“渠道确实收了钱”时收尾。
     * <p>
     * 要解决的问题：回调丢失时会出现“用户付了钱、订单却被超时取消”，
     * 而唯一的发现途径就是<b>主动问渠道</b>——渠道侧确实有这笔交易，我们的回调没收到。
     * <p>
     * 两个刻意的设计：
     * <ul>
     *   <li><b>先查渠道再退款，不做“盲退”</b>。抢票场景里绝大多数被取消的订单是“用户根本没付钱”，
     *       盲退不但无效（支付服务会因“账单不是已支付”而拒绝），还会把大量无效请求压到支付服务上；</li>
     *   <li><b>不把订单“补成已支付”</b>。发现“渠道已支付 + 本地已取消”时只走退款：
     *       这单被取消后座位可能已经卖给别人了，事后补成已支付会变成一票两卖，
     *       退款是唯一安全的结果（和回调里的处理保持一致）。</li>
     * </ul>
     *
     * @param order 待核对的订单（渠道从 {@code payOrderType} 取）
     * @return 本次核对的结果，调用方据此更新对账状态与指标
     */
    public PaymentReconcileResult reconcilePayment(Order order) {
        PayChannel payChannel = PayChannel.getRc(order.getPayOrderType());
        if (Objects.isNull(payChannel)) {
            // 从没发起过支付（发起支付时才会落 payOrderType），没有渠道可查
            return PaymentReconcileResult.NO_CHANNEL;
        }
        TradeCheckDto tradeCheckDto = new TradeCheckDto();
        tradeCheckDto.setOutTradeNo(String.valueOf(order.getOrderNumber()));
        tradeCheckDto.setChannel(payChannel.getValue());
        ApiResponse<TradeCheckVo> tradeCheckResponse;
        try {
            tradeCheckResponse = payClient.tradeCheck(tradeCheckDto);
        } catch (Exception e) {
            log.error("支付对账：查渠道异常 orderNumber : {}", order.getOrderNumber(), e);
            return PaymentReconcileResult.CHECK_FAILED;
        }
        TradeCheckVo tradeCheckVo = tradeCheckResponse.getData();
        if (!Objects.equals(tradeCheckResponse.getCode(), BaseCode.SUCCESS.getCode()) || Objects.isNull(tradeCheckVo)) {
            log.error("支付对账：查渠道失败 orderNumber : {} response : {}",
                    order.getOrderNumber(), JSON.toJSONString(tradeCheckResponse));
            return PaymentReconcileResult.CHECK_FAILED;
        }
        // 渠道没收到钱：这是绝大多数“取消了但从未支付”的订单，不用做任何事
        if (!tradeCheckVo.isSuccess()
                || !Objects.equals(tradeCheckVo.getPayBillStatus(), PayBillStatus.PAY.getCode())) {
            return PaymentReconcileResult.NOT_PAID;
        }
        // 渠道确实收了钱，而本地这单已经是已取消 —— 回调丢了才会出现的状态，退款
        log.warn("支付对账：渠道已支付但本地订单已取消，进入退款 orderNumber : {}", order.getOrderNumber());
        boolean refunded = refundClosedOrder(String.valueOf(order.getOrderNumber()),
                order.getOrderPrice(), payChannel.getValue());
        return refunded ? PaymentReconcileResult.REFUNDED : PaymentReconcileResult.REFUND_FAILED;
    }

    /**
     * 更新支付对账状态。写入失败不回滚、也不抛异常：它只影响“会不会重复核对一遍”，不影响资金结果。
     *
     * @param orderNumber 订单号
     * @param status      对账状态
     */
    public void markPaymentReconciled(Long orderNumber, ReconciliationStatus status) {
        Order updateOrder = new Order();
        updateOrder.setPayReconciliationStatus(status.getCode());
        updateOrder.setEditTime(DateUtils.now());
        try {
            orderMapper.update(updateOrder, Wrappers.lambdaUpdate(Order.class)
                    .eq(Order::getOrderNumber, orderNumber));
        } catch (Exception e) {
            log.error("支付对账状态更新失败 orderNumber : {} status : {}", orderNumber, status.getCode(), e);
        }
    }

    /**
     * 用带等待上限的方式拿订单锁。
     * <p>
     * 为什么不能 {@code lock.lock()} 无限等：支付回调的线程是<b>渠道的入账入口</b>，
     * 而锁内还会调支付服务（对账/退款），Redis 慢、或者交易对方卡住时，
     * 无限等待会把回调线程成片地挂在这里，进而拖垮整个服务的线程池——
     * 连普通查询和对账任务都会一起受害。
     * <p>
     * 拿不到锁就返回 false，让渠道按它自己的重试阶梯再来：
     * 渠道本来就有重试机制（我们也是靠它兜住“回调丢了”的情况），比我们死等划算。
     *
     * @return true = 已持有锁（调用方必须在 finally 里释放）；false = 没拿到，不要释放
     */
    private boolean tryLockOrderLock(RLock lock, String outTradeNo) {
        try {
            if (lock.tryLock(ORDER_LOCK_WAIT_SECONDS, TimeUnit.SECONDS)) {
                return true;
            }
            log.warn("支付回调等待订单锁超时，交由渠道重试 outTradeNo : {}", outTradeNo);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("支付回调等待订单锁被中断，交由渠道重试 outTradeNo : {}", outTradeNo, e);
        } catch (Exception e) {
            log.error("支付回调获取订单锁异常，交由渠道重试 outTradeNo : {}", outTradeNo, e);
        }
        return false;
    }

    /**
     * 判断异常是否属于“订单状态已经被另一条流程改掉”——这是并发仲裁的正常结果，不是执行失败。
     * 条件更新没抢到（ORDER_STATUS_CHANGED），或者读到的状态已经不合法
     * （ORDER_CANCEL / ORDER_PAY / ORDER_REFUND）都归到这一类。
     */
    private boolean isOrderStatusAlreadyChanged(Exception e) {
        if (!(e instanceof TicketFlowFrameException ticketFlowFrameException)) {
            return false;
        }
        Integer code = ticketFlowFrameException.getCode();
        return Objects.equals(code, BaseCode.ORDER_STATUS_CHANGED.getCode())
                || Objects.equals(code, BaseCode.ORDER_CANCEL.getCode())
                || Objects.equals(code, BaseCode.ORDER_PAY.getCode())
                || Objects.equals(code, BaseCode.ORDER_REFUND.getCode());
    }

    /**
     * 支付回调里更新订单状态抛异常时的统一收尾。
     * <p>
     * 分两类：
     * <ul>
     *   <li>“状态已被另一条流程改掉”→ 按订单最新状态收尾（已取消就退款），应答成功；</li>
     *   <li>真正的执行失败（事务已回滚，订单根本没更新）→ 应答失败让渠道重试。
     *       这里不能应答成功：钱已经到账、订单却没动，告诉渠道“成功”等于把这笔支付永久丢掉。</li>
     * </ul>
     *
     * @return 对渠道应答的结果
     */
    private String settlePayCallbackFailure(Exception e, Long orderNumber, String channel,
                                            String successResult, String failureResult) {
        if (isOrderStatusAlreadyChanged(e)) {
            return settleWhenOrderStatusChanged(orderNumber, channel) ? successResult : failureResult;
        }
        log.error("支付回调更新订单状态失败，等待渠道重试 orderNumber : {} channel : {}", orderNumber, channel, e);
        return failureResult;
    }

    /**
     * 订单状态已经不在“未支付”时的收尾：按订单最新状态决定后续动作。
     * 渠道刚确认支付成功，所以这里不能只记一条日志——
     * 订单已被取消（延迟订单关闭）就必须退款，否则就是钱收了、票没给、也没人退。
     *
     * @return true = 已妥善处理（可对渠道应答成功）；false = 退款失败，需要渠道重试
     */
    private boolean settleWhenOrderStatusChanged(Long orderNumber, String channel) {
        Order latest;
        try {
            latest = orderMapper.selectOne(Wrappers.lambdaQuery(Order.class)
                    .eq(Order::getOrderNumber, orderNumber));
        } catch (Exception e) {
            log.error("支付回调状态已被变更后查询订单失败 orderNumber : {}", orderNumber, e);
            return false;
        }
        if (Objects.isNull(latest)) {
            log.error("支付回调状态已被变更但订单不存在 orderNumber : {}", orderNumber);
            return false;
        }
        if (Objects.equals(latest.getOrderStatus(), OrderStatus.CANCEL.getCode())) {
            log.warn("支付已到账但订单已被取消，进入退款 orderNumber : {} channel : {}", orderNumber, channel);
            return refundClosedOrder(String.valueOf(orderNumber), latest.getOrderPrice(), channel);
        }
        // 已被另一条支付回调置为已支付，或已退单：这笔支付已经处理过，不需要再动
        log.info("支付回调状态已被变更，订单当前状态 {} orderNumber : {}",
                latest.getOrderStatus(), orderNumber);
        return true;
    }

    /**
     * 对“支付已到账、但订单已关闭（已取消）”的订单发起退款，并把订单置为已退单。
     * 退款失败必须返回 false 让渠道重试回调——应答成功会让这笔退款永久丢失。
     */
    private boolean refundClosedOrder(String outTradeNo, BigDecimal amount, String channel) {
        RefundDto refundDto = new RefundDto();
        refundDto.setOrderNumber(outTradeNo);
        refundDto.setAmount(amount);
        refundDto.setChannel(channel);
        refundDto.setReason("延迟订单关闭");
        ApiResponse<String> response = payClient.refund(refundDto);
        if (!Objects.equals(response.getCode(), BaseCode.SUCCESS.getCode())) {
            log.error("pay服务退款失败 dto : {} response : {}", JSON.toJSONString(refundDto), JSON.toJSONString(response));
            return false;
        }
        Order updateOrder = new Order();
        updateOrder.setEditTime(DateUtils.now());
        updateOrder.setOrderStatus(OrderStatus.REFUND.getCode());
        orderMapper.update(updateOrder, Wrappers.lambdaUpdate(Order.class)
                .eq(Order::getOrderNumber, Long.parseLong(outTradeNo)));
        log.info("订单已关闭，退款完成并置为已退单 outTradeNo : {}", outTradeNo);
        return true;
    }

    // 支付/取消核心编排：
    // 事务内只处理 Order、OrderTicketUser、流水等本地 DB 写操作；
    // 事务提交后再执行 Redis、Lua、Feign 等非事务操作。
    @Transactional(rollbackFor = Exception.class)
    public void updateOrderRelatedData(Long orderNumber, OrderStatus orderStatus) {

        // 1. 只允许 PAY / CANCEL 两种状态变更。
        if (!(Objects.equals(orderStatus.getCode(), OrderStatus.CANCEL.getCode()) ||
                Objects.equals(orderStatus.getCode(), OrderStatus.PAY.getCode()))) {
            throw new TicketFlowFrameException(BaseCode.OPERATE_ORDER_STATUS_NOT_PERMIT);
        }

        // 2. 查询订单并校验当前状态是否合法，防止重复支付、重复取消。
        LambdaQueryWrapper<Order> orderLambdaQueryWrapper =
                Wrappers.lambdaQuery(Order.class).eq(Order::getOrderNumber, orderNumber);
        Order order = orderMapper.selectOne(orderLambdaQueryWrapper);
        checkOrderStatus(order);

        // 条件更新（CAS）的期望状态：本次迁移的前置状态必须仍然是它。
        // checkOrderStatus 已经把“当前不是未支付”的情况拦掉了，所以这里取到的就是本次迁移的期望值。
        Integer expectStatus = order.getOrderStatus();

        // 3. 查询订单下的票务用户记录，后续状态更新和流水记录都基于此数据。
        LambdaQueryWrapper<OrderTicketUser> orderTicketUserLambdaQueryWrapper =
                Wrappers.lambdaQuery(OrderTicketUser.class)
                        .eq(OrderTicketUser::getOrderNumber, order.getOrderNumber());
        List<OrderTicketUser> orderTicketUserList =
                orderTicketUserMapper.selectList(orderTicketUserLambdaQueryWrapper);

        if (CollectionUtil.isEmpty(orderTicketUserList)) {
            throw new TicketFlowFrameException(BaseCode.TICKET_USER_ORDER_NOT_EXIST);
        }

        // 4. 准备 Order 和 OrderTicketUser 的状态更新。
        Order updateOrder = new Order();
        updateOrder.setId(order.getId());
        updateOrder.setOrderStatus(orderStatus.getCode());

        OrderTicketUser updateOrderTicketUser = new OrderTicketUser();
        updateOrderTicketUser.setOrderStatus(orderStatus.getCode());

        Integer recordTypeCode = RecordType.CHANGE_STATUS.getCode();
        String recordTypeValue = RecordType.CHANGE_STATUS.getValue();

        if (Objects.equals(orderStatus.getCode(), OrderStatus.PAY.getCode())) {
            updateOrder.setPayOrderTime(DateUtils.now());
            updateOrderTicketUser.setPayOrderTime(DateUtils.now());
        } else if (Objects.equals(orderStatus.getCode(), OrderStatus.CANCEL.getCode())) {
            updateOrder.setCancelOrderTime(DateUtils.now());
            updateOrderTicketUser.setCancelOrderTime(DateUtils.now());

            // 取消订单意味着释放资源，因此流水类型记录为 INCREASE。
            recordTypeCode = RecordType.INCREASE.getCode();
            recordTypeValue = RecordType.INCREASE.getValue();
        }

        // 5. 事务内更新订单和订单票务状态。
        // 主订单用条件更新：把步骤 2 读到的状态写进 WHERE，让数据库承担最后一道裁决。
        // 分工：@ServiceLock 负责让同一订单的流程尽量不打架（降低竞争），
        // 这里的前置状态条件负责最终正确性——即使锁在故障切换的窗口里失效，
        // 两个状态迁移也不可能同时成功：影响行数为 1 的那个才是赢家。
        LambdaUpdateWrapper<Order> orderLambdaUpdateWrapper =
                Wrappers.lambdaUpdate(Order.class)
                        .eq(Order::getOrderNumber, order.getOrderNumber())
                        .eq(Order::getOrderStatus, expectStatus);
        int updateOrderResult = orderMapper.update(updateOrder, orderLambdaUpdateWrapper);

        LambdaUpdateWrapper<OrderTicketUser> orderTicketUserLambdaUpdateWrapper =
                Wrappers.lambdaUpdate(OrderTicketUser.class)
                        .eq(OrderTicketUser::getOrderNumber, order.getOrderNumber());
        int updateTicketUserOrderResult =
                orderTicketUserMapper.update(updateOrderTicketUser, orderTicketUserLambdaUpdateWrapper);

        // 主订单影响行数为 0：本次状态迁移没有抢到（状态已被另一条流程改掉），
        // 这是并发仲裁的正常结果，不是故障，用独立错误码让调用方按订单最新状态收尾。
        if (updateOrderResult <= 0) {
            throw new TicketFlowFrameException(BaseCode.ORDER_STATUS_CHANGED);
        }
        // 购票人订单更新失败：数据层面的异常，回滚整个本地事务。
        if (updateTicketUserOrderResult <= 0) {
            throw new TicketFlowFrameException(BaseCode.ORDER_CANAL_ERROR);
        }

        // 6. 构建状态变更流水，同时记录订单对应的座位信息。
        List<SeatIdAndTicketUserIdDomain> seatIdAndTicketUserIdDomainList = new ArrayList<>();
        List<OrderTicketUserRecord> orderTicketUserRecordList = new ArrayList<>();

        for (OrderTicketUser orderTicketUser : orderTicketUserList) {
            OrderTicketUserRecord orderTicketUserRecord = new OrderTicketUserRecord();
            BeanUtils.copyProperties(orderTicketUser, orderTicketUserRecord);
            orderTicketUserRecord.setId(uidGenerator.getUid());
            orderTicketUserRecord.setIdentifierId(order.getIdentifierId());
            orderTicketUserRecord.setTicketUserOrderId(orderTicketUser.getId());
            orderTicketUserRecord.setRecordTypeCode(recordTypeCode);
            orderTicketUserRecord.setRecordTypeValue(recordTypeValue);

            orderTicketUserRecordList.add(orderTicketUserRecord);

            seatIdAndTicketUserIdDomainList.add(
                    new SeatIdAndTicketUserIdDomain(
                            orderTicketUser.getSeatId(),
                            orderTicketUser.getTicketUserId()));
        }

        // 流水与订单状态放在同一个本地事务中，保证状态变化和操作记录一致。
        orderTicketUserRecordService.saveBatch(orderTicketUserRecordList);

        // 7. 提前整理节目维度的座位数据，供事务提交后的缓存/库存操作使用。
        Long programId = order.getProgramId();

        Map<Long, List<OrderTicketUser>> orderTicketUserSeatList =
                orderTicketUserList.stream()
                        .collect(Collectors.groupingBy(OrderTicketUser::getTicketCategoryId));

        Map<Long, List<Long>> seatMap = new HashMap<>(orderTicketUserSeatList.size());
        orderTicketUserSeatList.forEach((k, v) -> {
            seatMap.put(k,
                    v.stream()
                            .map(OrderTicketUser::getSeatId)
                            .collect(Collectors.toList()));
        });

        // 8. 事务提交后执行 Redis / Lua / Feign 等操作。
        // 订单状态已经提交，后续操作失败不再回滚订单事务，由对账任务最终修复。
        Runnable afterCommitWork = () -> {
            try {

                // 取消订单时回减用户维度的订单数量统计。
                if (Objects.equals(orderStatus.getCode(), OrderStatus.CANCEL.getCode())) {
                    redisCache.incrBy(
                            RedisKeyBuild.createRedisKey(
                                    RedisKeyManage.ACCOUNT_ORDER_COUNT,
                                    order.getUserId(),
                                    order.getProgramId()),
                            -updateTicketUserOrderResult);
                }

                // 根据版本执行对应的座位、库存及节目数据更新。
                // V1-V3：Lua + 延迟任务收敛 DB；
                // V4/V5：Lua + Feign 直接推动 DB 收敛。
                updateProgramRelatedDataResolution(
                        programId,
                        seatMap,
                        orderStatus,
                        order.getIdentifierId(),
                        order.getUserId(),
                        seatIdAndTicketUserIdDomainList,
                        order.getOrderVersion());

            } catch (Exception e) {
                // afterCommit 失败不能再回滚已提交事务，交由后续对账任务兜底。
                log.error("支付/取消后数据同步失败 订单号 : {} 状态 : {}",
                        orderNumber, orderStatus, e);
            }
        };

        // 9. 正常事务场景下注册 afterCommit，确保只有 DB 事务提交成功后才执行后续同步。
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    afterCommitWork.run();
                }
            });
        } else {
            // 无事务上下文时直接执行，兼容自调用、单元测试等场景。
            afterCommitWork.run();
        }
    }
    public void checkOrderStatus(Order order) {
        if (Objects.isNull(order)) {
            throw new TicketFlowFrameException(BaseCode.ORDER_NOT_EXIST);
        }
        if (Objects.equals(order.getOrderStatus(), OrderStatus.CANCEL.getCode())) {
            throw new TicketFlowFrameException(BaseCode.ORDER_CANCEL);
        }
        if (Objects.equals(order.getOrderStatus(), OrderStatus.PAY.getCode())) {
            throw new TicketFlowFrameException(BaseCode.ORDER_PAY);
        }
        if (Objects.equals(order.getOrderStatus(), OrderStatus.REFUND.getCode())) {
            throw new TicketFlowFrameException(BaseCode.ORDER_REFUND);
        }
    }

    /**
     * 构造 4 个 JSON 参数传给 OrderProgramDataResolution.lua：
     * data[0] = unLockSeatIdjsonArray   —— 从 LOCK hash 删除的座位
     * data[1] = addSeatDatajsonArray     —— 写入 NO_SOLD / SOLD hash 的座位
     * data[2] = jsonArray                —— 调整余票数量（cancel 加回 / pay 扣减）
     * data[3] = seatIdAndTicketUserIdDomainList —— 购票人关联记录
     * V4 路径额外走 Feign 调用 operateProgramData 更新 DB（DB 状态统一切换）。
     *
     * @param orderVersion 见 ProgramOrderVersion：V1=1, V2=2, V3=3, V4=4
     */
    public void updateProgramRelatedDataResolution(Long programId, Map<Long, List<Long>> seatMap, OrderStatus orderStatus, Long identifierId, Long userId,
                                                   List<SeatIdAndTicketUserIdDomain> seatIdAndTicketUserIdDomainList,
                                                   Integer orderVersion) {
        Map<Long, List<SeatVo>> seatVoMap = new HashMap<>(seatMap.size());
        seatMap.forEach((k, v) -> {
            seatVoMap.put(k, redisCache.multiGetForHash(
                    RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, programId, k),
                    v.stream().map(String::valueOf).collect(Collectors.toList()), SeatVo.class));
        });
        if (CollectionUtil.isEmpty(seatVoMap)) {
            throw new TicketFlowFrameException(BaseCode.LOCK_SEAT_LIST_EMPTY);
        }
        JSONArray jsonArray = new JSONArray();
        JSONArray addSeatDatajsonArray = new JSONArray();
        List<TicketCategoryCountDto> ticketCategoryCountDtoList = new ArrayList<>(seatVoMap.size());
        JSONArray unLockSeatIdjsonArray = new JSONArray();
        List<Long> unLockSeatIdList = new ArrayList<>();
        seatVoMap.forEach((k, v) -> {
            JSONObject unLockSeatIdjsonObject = new JSONObject();
            unLockSeatIdjsonObject.put("programSeatLockHashKey", RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, programId, k).getRelKey());
            unLockSeatIdjsonObject.put("unLockSeatIdList", v.stream()
                    .map(SeatVo::getId).map(String::valueOf).collect(Collectors.toList()));
            unLockSeatIdjsonArray.add(unLockSeatIdjsonObject);
            JSONObject seatDatajsonObject = new JSONObject();
            String seatHashKeyAdd = "";
            if (Objects.equals(orderStatus.getCode(), OrderStatus.CANCEL.getCode())) {
                seatHashKeyAdd = RedisKeyBuild.createRedisKey(
                        RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, programId, k).getRelKey();
                for (SeatVo seatVo : v) {
                    seatVo.setSellStatus(SellStatus.NO_SOLD.getCode());
                }
            } else if (Objects.equals(orderStatus.getCode(), OrderStatus.PAY.getCode())) {
                seatHashKeyAdd = RedisKeyBuild.createRedisKey(
                        RedisKeyManage.PROGRAM_SEAT_SOLD_RESOLUTION_HASH, programId, k).getRelKey();
                for (SeatVo seatVo : v) {
                    seatVo.setSellStatus(SellStatus.SOLD.getCode());
                }
            }
            seatDatajsonObject.put("seatHashKeyAdd", seatHashKeyAdd);
            List<String> seatDataList = new ArrayList<>();
            for (SeatVo seatVo : v) {
                seatDataList.add(String.valueOf(seatVo.getId()));
                seatDataList.add(JSON.toJSONString(seatVo));
            }
            seatDatajsonObject.put("seatDataList", seatDataList);
            addSeatDatajsonArray.add(seatDatajsonObject);
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("programTicketRemainNumberHashKey", RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION, programId, k).getRelKey());
            jsonObject.put("ticketCategoryId", String.valueOf(k));
            jsonObject.put("count", v.size());
            jsonArray.add(jsonObject);
            TicketCategoryCountDto ticketCategoryCountDto = new TicketCategoryCountDto();
            ticketCategoryCountDto.setTicketCategoryId(k);
            ticketCategoryCountDto.setCount((long) v.size());
            ticketCategoryCountDtoList.add(ticketCategoryCountDto);

            unLockSeatIdList.addAll(v.stream().map(SeatVo::getId).toList());
        });
        List<String> keys = new ArrayList<>();
        keys.add(String.valueOf(orderStatus.getCode()));
        keys.add(String.valueOf(programId));
        keys.add(RedisKeyBuild.getRedisKey(RedisKeyManage.PROGRAM_RECORD));

        String recordTye = Objects.equals(orderStatus.getCode(), OrderStatus.CANCEL.getCode()) ? RecordType.INCREASE.getValue() : RecordType.CHANGE_STATUS.getValue();
        keys.add(recordTye + GLIDE_LINE + identifierId + GLIDE_LINE + userId);
        keys.add(recordTye);
        Object[] data = new String[4];
        data[0] = JSON.toJSONString(unLockSeatIdjsonArray);
        data[1] = JSON.toJSONString(addSeatDatajsonArray);
        data[2] = JSON.toJSONString(jsonArray);
        data[3] = JSON.toJSONString(seatIdAndTicketUserIdDomainList);

        ProgramOperateDataDto programOperateDataDto = new ProgramOperateDataDto();
        programOperateDataDto.setProgramId(programId);
        programOperateDataDto.setSeatIdList(unLockSeatIdList);
        programOperateDataDto.setTicketCategoryCountDtoList(ticketCategoryCountDtoList);
        programOperateDataDto.setOrderVersion(orderVersion);
        //V5 与 V4 一致：走同步 Feign 更新 program 侧 DB（pay/cancel 时 DB 座位/余票收敛）
        boolean isV5 = ProgramOrderVersion.V5_VERSION.getValue().equals(orderVersion);
        //如果创建订单版本是v1，v2，v3
        if (!orderVersion.equals(ProgramOrderVersion.V4_VERSION.getValue()) && !isV5) {
            orderProgramCacheResolutionOperate.programCacheReverseOperate(keys, data);
            if (Objects.equals(orderStatus.getCode(), OrderStatus.PAY.getCode())) {
                programOperateDataDto.setSellStatus(SellStatus.SOLD.getCode());
                delayOperateProgramDataSend.sendMessage(JSON.toJSONString(programOperateDataDto));
            }
        } else {
            //V4/V5：先 Lua 收敛 Redis 权威数据（座位三区/余票），再 Feign 收敛 DB 派生数据。
            //Redis 为权威，先更新；DB 为派生，后收敛——Feign 失败仅记日志（DB 滞后由投影/守恒任务观测兜底）
            orderProgramCacheResolutionOperate.programCacheReverseOperate(keys, data);
            if (Objects.equals(orderStatus.getCode(), OrderStatus.PAY.getCode()) ||
                    Objects.equals(orderStatus.getCode(), OrderStatus.CANCEL.getCode())) {
                programOperateDataDto.setSellStatus(Objects.equals(orderStatus.getCode(), OrderStatus.PAY.getCode()) ? SellStatus.SOLD.getCode() : SellStatus.NO_SOLD.getCode());
                // 事务外尽力而为：Feign 失败仅记日志不阻断（Redis 已收敛），
                // DB 侧由投影任务/V5StockConservationTask 观测兜底
                try {
                    ApiResponse<Boolean> programApiResponse = programClient.operateProgramData(programOperateDataDto);
                    if (!Objects.equals(programApiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
                        log.error("program服务 operateProgramData 失败（事务外尽力而为） dto : {} response : {}",
                                JSON.toJSONString(programOperateDataDto), JSON.toJSONString(programApiResponse));
                    }
                } catch (Exception e) {
                    log.error("program服务 operateProgramData 异常（事务外尽力而为，DB 侧由投影任务/守恒任务观测兜底） dto : {}",
                            JSON.toJSONString(programOperateDataDto), e);
                }
            }
        }
    }

    public List<OrderListVo> selectList(OrderListDto orderListDto) {
        List<OrderListVo> orderListVos = new ArrayList<>();
        LambdaQueryWrapper<Order> orderLambdaQueryWrapper =
                Wrappers.lambdaQuery(Order.class)
                        .eq(Order::getUserId, orderListDto.getUserId())
                        .orderByDesc(Order::getCreateOrderTime);
        List<Order> orderList = orderMapper.selectList(orderLambdaQueryWrapper);
        if (CollectionUtil.isEmpty(orderList)) {
            return orderListVos;
        }
        orderListVos = BeanUtil.copyToList(orderList, OrderListVo.class);
        List<OrderTicketUserAggregate> orderTicketUserAggregateList =
                orderTicketUserMapper.selectOrderTicketUserAggregate(orderList.stream().map(Order::getOrderNumber).
                        collect(Collectors.toList()));
        Map<Long, Integer> orderTicketUserAggregateMap = orderTicketUserAggregateList.stream()
                .collect(Collectors.toMap(OrderTicketUserAggregate::getOrderNumber,
                        OrderTicketUserAggregate::getOrderTicketUserCount, (v1, v2) -> v2));
        for (OrderListVo orderListVo : orderListVos) {
            orderListVo.setTicketCount(orderTicketUserAggregateMap.get(orderListVo.getOrderNumber()));
        }
        return orderListVos;
    }

    public OrderGetVo get(OrderGetDto orderGetDto) {
        LambdaQueryWrapper<Order> orderLambdaQueryWrapper =
                Wrappers.lambdaQuery(Order.class).eq(Order::getOrderNumber, orderGetDto.getOrderNumber());
        Order order = orderMapper.selectOne(orderLambdaQueryWrapper);
        if (Objects.isNull(order)) {
            throw new TicketFlowFrameException(BaseCode.ORDER_NOT_EXIST);
        }
        LambdaQueryWrapper<OrderTicketUser> orderTicketUserLambdaQueryWrapper =
                Wrappers.lambdaQuery(OrderTicketUser.class).eq(OrderTicketUser::getOrderNumber, order.getOrderNumber());
        List<OrderTicketUser> orderTicketUserList = orderTicketUserMapper.selectList(orderTicketUserLambdaQueryWrapper);
        if (CollectionUtil.isEmpty(orderTicketUserList)) {
            throw new TicketFlowFrameException(BaseCode.TICKET_USER_ORDER_NOT_EXIST);
        }

        OrderGetVo orderGetVo = new OrderGetVo();
        BeanUtil.copyProperties(order, orderGetVo);

        List<OrderTicketInfoVo> orderTicketInfoVoList = new ArrayList<>();
        Map<BigDecimal, List<OrderTicketUser>> orderTicketUserMap =
                orderTicketUserList.stream().collect(Collectors.groupingBy(OrderTicketUser::getOrderPrice));
        orderTicketUserMap.forEach((k, v) -> {
            OrderTicketInfoVo orderTicketInfoVo = new OrderTicketInfoVo();
            String seatInfo = v.stream()
                    .map(OrderTicketUser::getSeatInfo)
                    .filter(StringUtil::isNotEmpty)
                    .collect(Collectors.joining(","));
            if (StringUtil.isEmpty(seatInfo)) {
                seatInfo = "暂无座位信息";
            }
            orderTicketInfoVo.setSeatInfo(seatInfo);
            orderTicketInfoVo.setPrice(v.get(0).getOrderPrice());
            orderTicketInfoVo.setQuantity(v.size());
            orderTicketInfoVo.setRelPrice(v.stream().map(OrderTicketUser::getOrderPrice)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            orderTicketInfoVoList.add(orderTicketInfoVo);
        });

        orderGetVo.setOrderTicketInfoVoList(orderTicketInfoVoList);

        UserGetAndTicketUserListDto userGetAndTicketUserListDto = new UserGetAndTicketUserListDto();
        userGetAndTicketUserListDto.setUserId(order.getUserId());
        ApiResponse<UserGetAndTicketUserListVo> userGetAndTicketUserApiResponse =
                userClient.getUserAndTicketUserList(userGetAndTicketUserListDto);

        if (!Objects.equals(userGetAndTicketUserApiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
            throw new TicketFlowFrameException(userGetAndTicketUserApiResponse);

        }
        UserGetAndTicketUserListVo userAndTicketUserListVo =
                Optional.ofNullable(userGetAndTicketUserApiResponse.getData())
                        .orElseThrow(() -> new TicketFlowFrameException(BaseCode.RPC_RESULT_DATA_EMPTY));
        if (Objects.isNull(userAndTicketUserListVo.getUserVo())) {
            throw new TicketFlowFrameException(BaseCode.USER_EMPTY);
        }
        if (CollectionUtil.isEmpty(userAndTicketUserListVo.getTicketUserVoList())) {
            throw new TicketFlowFrameException(BaseCode.TICKET_USER_EMPTY);
        }
        Map<Long, TicketUserVo> ticketUserVoMap = userAndTicketUserListVo.getTicketUserVoList()
                .stream().collect(Collectors.toMap(TicketUserVo::getId, ticketUserVo -> ticketUserVo, (v1, v2) -> v2));
        List<TicketUserVo> filterTicketUserVoList = orderTicketUserList.stream()
                // 购票人可能已被删除导致缺失，过滤空条目避免下游生成全空对象
                .map(orderTicketUser -> ticketUserVoMap.get(orderTicketUser.getTicketUserId()))
                .filter(Objects::nonNull)
                .toList();
        UserInfoVo userInfoVo = new UserInfoVo();
        BeanUtil.copyProperties(userAndTicketUserListVo.getUserVo(), userInfoVo);
        UserAndTicketUserInfoVo userAndTicketUserInfoVo = new UserAndTicketUserInfoVo();
        userAndTicketUserInfoVo.setUserInfoVo(userInfoVo);
        userAndTicketUserInfoVo.setTicketUserInfoVoList(BeanUtil.copyToList(filterTicketUserVoList, TicketUserInfoVo.class));
        orderGetVo.setUserAndTicketUserInfoVo(userAndTicketUserInfoVo);

        return orderGetVo;
    }

    public AccountOrderCountVo accountOrderCount(AccountOrderCountDto accountOrderCountDto) {
        AccountOrderCountVo accountOrderCountVo = new AccountOrderCountVo();
        accountOrderCountVo.setCount(orderMapper.accountOrderCount(accountOrderCountDto.getUserId(),
                accountOrderCountDto.getProgramId()));
        return accountOrderCountVo;
    }


    // V4/V41 Kafka 消费者入口：先 Feign 锁定 program 侧的座位+库存，再在本事务建 DB 订单
    // V5：Redis 为唯一库存权威，建单不再同步 Feign 扣 DB（DB 座位/余票由投影任务/支付取消路径异步收敛）
    // 幂等：durationTime=0 不写幂等标记（省 2 次 GET + 1 次 SET，RTT 5→2），
    // 重复消息由 doCreate 的 selectOne 防重 + DB 唯一索引 d_order_order_number_IDX + ORDER_EXIST 幂等特判兜底。
    @RepeatExecuteLimit(name = CREATE_PROGRAM_ORDER_MQ, keys = {"#orderCreateMq.orderNumber"}, durationTime = 0)
    @Transactional(rollbackFor = Exception.class)
    public String createMq(OrderCreateMq orderCreateMq) {
        List<OrderTicketUserCreateDto> orderTicketUserCreateDtoList = orderCreateMq.getOrderTicketUserCreateDtoList();
        String orderNumber;
        try {
            if (Objects.equals(orderCreateMq.getOrderVersion(), ProgramOrderVersion.V5_VERSION.getValue())) {
                // V5：无第二权威，直接建单；Redis 已在请求侧完成扣减，消费侧不再重复扣 DB
                orderNumber = createByMq(orderCreateMq);
            } else {
                //使用 Stream API 按 ticketCategoryId 分组并计数
                Map<Long, Long> countMap = orderTicketUserCreateDtoList.stream()
                        .collect(Collectors.groupingBy(OrderTicketUserCreateDto::getTicketCategoryId, Collectors.counting()));
                //将统计结果转换为列表，存入 TicketCountDto 对象中
                List<TicketCategoryCountDto> ticketCountList = countMap.entrySet().stream()
                        .map(entry -> new TicketCategoryCountDto(entry.getKey(), entry.getValue()))
                        .toList();
                //修改节目服务中的座位状态和扣减库存
                ReduceRemainNumberDto reduceRemainNumberDto = new ReduceRemainNumberDto();
                reduceRemainNumberDto.setProgramId(orderCreateMq.getProgramId());
                reduceRemainNumberDto.setSellStatus(SellStatus.LOCK.getCode());
                reduceRemainNumberDto.setSeatIdList(orderTicketUserCreateDtoList.stream().map(OrderTicketUserCreateDto::getSeatId).collect(Collectors.toList()));
                reduceRemainNumberDto.setTicketCategoryCountDtoList(ticketCountList);
                ApiResponse<Boolean> programApiResponse = programClient.operateSeatLockAndTicketCategoryRemainNumber(reduceRemainNumberDto);
                if (!Objects.equals(programApiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
                    //丢弃记录统一由 CreateOrderConsumer 外层 catch 写入，此处不再重复写入
                    throw new TicketFlowFrameException(programApiResponse);
                }
                try {
                    //真正地创建订单
                    orderNumber = createByMq(orderCreateMq);
                } catch (Exception e) {
                    //Feign 已扣减 DB（座位 LOCK + 余票扣减）但本地建单失败（事务回滚，订单不存在）：
                    //远程扣减不受本地事务回滚影响，需显式反向恢复 DB（内部对"订单已存在"自动跳过）
                    rollbackProgramDataByCreateFail(orderCreateMq, reduceRemainNumberDto);
                    throw e;
                }
            }
        } catch (TicketFlowFrameException e) {
            // 重复消息（Kafka 重投/对账重放）：订单已存在视为幂等成功，不抛异常、不写 DISCARD_ORDER
            if (Objects.equals(e.getCode(), BaseCode.ORDER_EXIST.getCode())) {
                Order existOrder = orderMapper.selectOne(Wrappers.lambdaQuery(Order.class)
                        .eq(Order::getOrderNumber, orderCreateMq.getOrderNumber()));
                if (Objects.nonNull(existOrder)) {
                    log.info("重复建单消息 幂等成功 订单号 : {}", orderCreateMq.getOrderNumber());
                    return String.valueOf(existOrder.getOrderNumber());
                }
            }
            throw e;
        }
        // 建单完成标记：供前端 /order/get/cache 轮询终态，同时支撑 PENDING 对账的"已建单"判定。
        // TTL 10min > 对账 3min 滞后窗口（ReconciliationTask 按 3min 前的 ProgramRecordTask 触发），
        // 确保对账首次裁决该 PENDING 条目时标记尚未过期。
        redisCache.set(RedisKeyBuild.createRedisKey(RedisKeyManage.ORDER_MQ, orderNumber), orderNumber, 10, TimeUnit.MINUTES);
        return orderNumber;
    }

    /**
     * 建单失败后的 DB 反向恢复。
     * 反向恢复是补偿式操作，失败仅记日志不掩盖原建单异常，
     * 残留漂移由 DISCARD_ORDER 记录观测兜底。
     */
    private void rollbackProgramDataByCreateFail(OrderCreateMq orderCreateMq, ReduceRemainNumberDto reduceRemainNumberDto) {
        try {
            // 订单已存在（消息重放等）不执行反向恢复，避免释放已建订单的座位
            Long orderCount = orderMapper.selectCount(Wrappers.lambdaQuery(Order.class)
                    .eq(Order::getOrderNumber, orderCreateMq.getOrderNumber()));
            if (orderCount > 0) {
                log.info("建单失败反向恢复跳过 订单已存在 订单号 : {}", orderCreateMq.getOrderNumber());
                return;
            }
            ProgramOperateDataDto programOperateDataDto = new ProgramOperateDataDto();
            programOperateDataDto.setProgramId(orderCreateMq.getProgramId());
            programOperateDataDto.setSeatIdList(reduceRemainNumberDto.getSeatIdList());
            programOperateDataDto.setTicketCategoryCountDtoList(reduceRemainNumberDto.getTicketCategoryCountDtoList());
            programOperateDataDto.setSellStatus(SellStatus.NO_SOLD.getCode());
            programOperateDataDto.setOrderVersion(orderCreateMq.getOrderVersion());
            ApiResponse<Boolean> programApiResponse = programClient.operateProgramData(programOperateDataDto);
            if (!Objects.equals(programApiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
                log.error("建单失败反向恢复DB失败 订单号 : {} 响应 : {}", orderCreateMq.getOrderNumber(), JSON.toJSONString(programApiResponse));
            }
        } catch (Exception e) {
            log.error("建单失败反向恢复DB异常 订单号 : {}", orderCreateMq.getOrderNumber(), e);
        }
    }

    public String getCache(OrderGetDto orderGetDto) {
        return redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.ORDER_MQ, orderGetDto.getOrderNumber()), String.class);
    }

    /**
     * 丢弃订单（消息延迟超时被 CreateOrderConsumer 丢弃）的 Redis 座位回滚。
     * 订单从未入库，DB 座位/余票从未扣减，因此只回滚 Redis 缓存：
     * 座位 LOCK → NO_SOLD，余票恢复，写入 INCREASE 流水。
     * 安全约束：
     * 1. 订单已存在（如消息重放）不执行回滚，避免释放已建订单的座位
     * 2. 座位已不在锁定集合（如缓存重建后复活）自动跳过，避免余票虚增
     */
    public void rollbackProgramSeatByDiscard(OrderCreateMq orderCreateMq) {
        // 订单已存在（消息重放等）不执行回滚，避免释放已建订单的座位
        Long orderCount = orderMapper.selectCount(Wrappers.lambdaQuery(Order.class)
                .eq(Order::getOrderNumber, orderCreateMq.getOrderNumber()));
        if (orderCount > 0) {
            log.info("丢弃订单回滚跳过 订单已存在 订单号 : {}", orderCreateMq.getOrderNumber());
            return;
        }
        List<OrderTicketUserCreateDto> orderTicketUserCreateDtoList = orderCreateMq.getOrderTicketUserCreateDtoList();
        Map<Long, List<Long>> seatMap = orderTicketUserCreateDtoList.stream().collect(Collectors.groupingBy(
                OrderTicketUserCreateDto::getTicketCategoryId,
                Collectors.mapping(OrderTicketUserCreateDto::getSeatId, Collectors.toList())));
        // 只回滚仍在锁定集合中的座位；缓存重建后已复活的座位跳过，避免余票虚增
        Map<Long, List<SeatVo>> seatVoMap = new HashMap<>(seatMap.size());
        seatMap.forEach((k, v) -> {
            List<SeatVo> seatVoList = redisCache.multiGetForHash(
                    RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH,
                            orderCreateMq.getProgramId(), k),
                    v.stream().map(String::valueOf).collect(Collectors.toList()), SeatVo.class);
            if (CollectionUtil.isNotEmpty(seatVoList)) {
                seatVoMap.put(k, seatVoList);
            }
        });
        if (CollectionUtil.isEmpty(seatVoMap)) {
            log.info("丢弃订单回滚跳过 座位已不在锁定状态 订单号 : {}", orderCreateMq.getOrderNumber());
            return;
        }
        JSONArray unLockSeatIdjsonArray = new JSONArray();
        JSONArray addSeatDatajsonArray = new JSONArray();
        JSONArray jsonArray = new JSONArray();
        seatVoMap.forEach((k, v) -> {
            JSONObject unLockSeatIdjsonObject = new JSONObject();
            unLockSeatIdjsonObject.put("programSeatLockHashKey", RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, orderCreateMq.getProgramId(), k).getRelKey());
            unLockSeatIdjsonObject.put("unLockSeatIdList", v.stream()
                    .map(SeatVo::getId).map(String::valueOf).collect(Collectors.toList()));
            unLockSeatIdjsonArray.add(unLockSeatIdjsonObject);
            JSONObject seatDatajsonObject = new JSONObject();
            seatDatajsonObject.put("seatHashKeyAdd", RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH, orderCreateMq.getProgramId(), k).getRelKey());
            List<String> seatDataList = new ArrayList<>();
            for (SeatVo seatVo : v) {
                seatVo.setSellStatus(SellStatus.NO_SOLD.getCode());
                seatDataList.add(String.valueOf(seatVo.getId()));
                seatDataList.add(JSON.toJSONString(seatVo));
            }
            seatDatajsonObject.put("seatDataList", seatDataList);
            addSeatDatajsonArray.add(seatDatajsonObject);
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("programTicketRemainNumberHashKey", RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION, orderCreateMq.getProgramId(), k).getRelKey());
            jsonObject.put("ticketCategoryId", String.valueOf(k));
            jsonObject.put("count", v.size());
            jsonArray.add(jsonObject);
        });
        List<SeatIdAndTicketUserIdDomain> seatIdAndTicketUserIdDomainList = new ArrayList<>();
        for (OrderTicketUserCreateDto orderTicketUserCreateDto : orderTicketUserCreateDtoList) {
            seatIdAndTicketUserIdDomainList.add(new SeatIdAndTicketUserIdDomain(orderTicketUserCreateDto.getSeatId(),
                    orderTicketUserCreateDto.getTicketUserId()));
        }
        List<String> keys = new ArrayList<>();
        keys.add(String.valueOf(OrderStatus.CANCEL.getCode()));
        keys.add(String.valueOf(orderCreateMq.getProgramId()));
        keys.add(RedisKeyBuild.getRedisKey(RedisKeyManage.PROGRAM_RECORD));
        String recordType = RecordType.INCREASE.getValue();
        keys.add(recordType + GLIDE_LINE + orderCreateMq.getIdentifierId() + GLIDE_LINE + orderCreateMq.getUserId());
        keys.add(recordType);
        Object[] data = new String[4];
        data[0] = JSON.toJSONString(unLockSeatIdjsonArray);
        data[1] = JSON.toJSONString(addSeatDatajsonArray);
        data[2] = JSON.toJSONString(jsonArray);
        data[3] = JSON.toJSONString(seatIdAndTicketUserIdDomainList);
        orderProgramCacheResolutionOperate.programCacheReverseOperate(keys, data);
        // V5 对称减回限购计数：正向 Lua 在扣减成功时 INCRBY，回滚后必须减回，
        // 否则 DISCARD/PENDING 回滚的失败订单会永久占用用户限购配额（与正常取消路径语义对齐）。
        // 计数按实际回滚的座位数递减；seatVoMap 为空时已提前 return，不会误减。
        if (Objects.equals(orderCreateMq.getOrderVersion(), ProgramOrderVersion.V5_VERSION.getValue())) {
            int rolledBackSeatCount = seatVoMap.values().stream().mapToInt(List::size).sum();
            try {
                redisCache.incrBy(RedisKeyBuild.createRedisKey(RedisKeyManage.ACCOUNT_ORDER_COUNT,
                        orderCreateMq.getUserId(), orderCreateMq.getProgramId()), -rolledBackSeatCount);
            } catch (Exception e) {
                log.error("V5 丢弃订单回滚减回限购计数失败 需人工处理 orderNumber : {}",
                        orderCreateMq.getOrderNumber(), e);
            }
        }
        log.info("丢弃订单回滚Redis座位完成 订单号 : {}", orderCreateMq.getOrderNumber());
    }

    @RepeatExecuteLimit(name = CANCEL_PROGRAM_ORDER, keys = {"#orderCancelDto.orderNumber"})
    @ServiceLock(name = UPDATE_ORDER_STATUS_LOCK, keys = {"#orderCancelDto.orderNumber"})
    @Transactional(rollbackFor = Exception.class)
    public boolean initiateCancel(OrderCancelDto orderCancelDto) {
        Order order = orderMapper.selectOne(Wrappers.lambdaQuery(Order.class)
                .eq(Order::getOrderNumber, orderCancelDto.getOrderNumber()));
        if (Objects.isNull(order)) {
            throw new TicketFlowFrameException(BaseCode.ORDER_NOT_EXIST);
        }
        if (!Objects.equals(order.getOrderStatus(), OrderStatus.NO_PAY.getCode())) {
            throw new TicketFlowFrameException(BaseCode.CAN_NOT_CANCEL);
        }
        return cancel(orderCancelDto);
    }


    public void delOrderAndOrderTicketUser() {
        orderMapper.relDelOrder();
        orderTicketUserMapper.relDelOrderTicketUser();
        orderTicketUserRecordMapper.relDelOrderTicketUserRecord();
        orderProgramMapper.relDelOrderProgram();
    }

    public List<OrderListVo> simpleList(OrderSimpleListDto orderSimpleListDto) {
        if (Objects.isNull(orderSimpleListDto.getOrderNumber()) && Objects.isNull(orderSimpleListDto.getUserId())) {
            throw new TicketFlowFrameException(BaseCode.USER_ID_AND_ORDER_NUMBER_NOT_EXIST);
        }
        List<OrderListVo> orderListVos = new ArrayList<>();
        LambdaQueryWrapper<Order> orderLambdaQueryWrapper =
                Wrappers.lambdaQuery(Order.class)
                        .eq(Objects.nonNull(orderSimpleListDto.getOrderNumber()), Order::getOrderNumber, orderSimpleListDto.getOrderNumber())
                        .eq(Objects.nonNull(orderSimpleListDto.getUserId()), Order::getUserId, orderSimpleListDto.getUserId())
                        .orderByDesc(Order::getCreateOrderTime);
        List<Order> orderList = orderMapper.selectList(orderLambdaQueryWrapper);
        if (CollectionUtil.isEmpty(orderList)) {
            return orderListVos;
        }
        return BeanUtil.copyToList(orderList, OrderListVo.class);
    }
}
