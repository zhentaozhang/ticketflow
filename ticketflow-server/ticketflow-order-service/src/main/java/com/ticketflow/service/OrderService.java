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
import com.ticketflow.enums.DiscardOrderReason;
import com.ticketflow.enums.OrderStatus;
import com.ticketflow.enums.PayBillStatus;
import com.ticketflow.enums.PayChannel;
import com.ticketflow.enums.ProgramOrderVersion;
import com.ticketflow.enums.RecordType;
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
 *   创建（V2/V3 同步/V4 异步三种策略统一入口）
 *   支付回调（支付宝 notify 签名校验 → 状态流转）
 *   取消（延迟队列超时 → 状态回滚）
 *   对账（补偿记录写回、清理过期 lock 占位）
 *   管理（后台批量关闭、重置调度）
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
        BeanUtil.copyProperties(orderCreateDomain,order);
        order.setId(uidGenerator.getUid());
        order.setDistributionMode("电子票");
        order.setTakeTicketMode("请使用购票人身份证直接入场");
        //购票人订单对象
        List<OrderTicketUser> orderTicketUserList = new ArrayList<>();
        //购票人订单记录对象
        List<OrderTicketUserRecord> orderTicketUserRecordList = new ArrayList<>();
        for (OrderTicketUserCreateDto orderTicketUserCreateDto : orderCreateDomain.getOrderTicketUserCreateDtoList()) {
            OrderTicketUser orderTicketUser = new OrderTicketUser();
            BeanUtil.copyProperties(orderTicketUserCreateDto,orderTicketUser);
            orderTicketUser.setId(uidGenerator.getUid());
            orderTicketUserList.add(orderTicketUser);

            OrderTicketUserRecord orderTicketUserRecord = new OrderTicketUserRecord();
            BeanUtil.copyProperties(orderTicketUserCreateDto,orderTicketUserRecord);
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
        redisCache.incrBy(RedisKeyBuild.createRedisKey(
                RedisKeyManage.ACCOUNT_ORDER_COUNT,orderCreateDomain.getUserId(),
                orderCreateDomain.getProgramId()),orderCreateDomain.getOrderTicketUserCreateDtoList().size());
        return String.valueOf(order.getOrderNumber());
    }
    
    /**
     * 订单取消（幂等入口）。
     * @RepeatExecuteLimit 防止相同 orderNumber 重复执行
     * @ServiceLock(Reentrant) 防止并发取消 + 支付同时进入
     */
    @RepeatExecuteLimit(name = CANCEL_PROGRAM_ORDER,keys = {"#orderCancelDto.orderNumber"})
    @ServiceLock(name = UPDATE_ORDER_STATUS_LOCK,keys = {"#orderCancelDto.orderNumber"})
    @Transactional(rollbackFor = Exception.class)
    public boolean cancel(OrderCancelDto orderCancelDto){
        updateOrderRelatedData(orderCancelDto.getOrderNumber(),OrderStatus.CANCEL);
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
     * */
    @ServiceLock(name = UPDATE_ORDER_STATUS_LOCK,keys = {"#orderPayCheckDto.orderNumber"})
    public OrderPayCheckVo payCheck(OrderPayCheckDto orderPayCheckDto){
        OrderPayCheckVo orderPayCheckVo = new OrderPayCheckVo();
        LambdaQueryWrapper<Order> orderLambdaQueryWrapper =
                Wrappers.lambdaQuery(Order.class).eq(Order::getOrderNumber, orderPayCheckDto.getOrderNumber());
        Order order = orderMapper.selectOne(orderLambdaQueryWrapper);
        if (Objects.isNull(order)) {
            throw new TicketFlowFrameException(BaseCode.ORDER_NOT_EXIST);
        }
        BeanUtil.copyProperties(order,orderPayCheckVo);
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
                orderMapper.update(updateOrder,Wrappers.lambdaUpdate(Order.class).eq(Order::getOrderNumber, order.getOrderNumber()));
            }else {
                log.error("pay服务退款失败 dto : {} response : {}",JSON.toJSONString(refundDto),JSON.toJSONString(response));
            }
            orderPayCheckVo.setOrderStatus(OrderStatus.REFUND.getCode());
            orderPayCheckVo.setCancelOrderTime(DateUtils.now());
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
                        orderService.updateOrderRelatedData(order.getOrderNumber(),OrderStatus.PAY);
                    }else if (Objects.equals(payBillStatus, PayBillStatus.CANCEL.getCode())) {
                        orderPayCheckVo.setCancelOrderTime(DateUtils.now());
                        orderService.updateOrderRelatedData(order.getOrderNumber(),OrderStatus.CANCEL);
                    }
                }catch (Exception e) {
                    log.warn("updateOrderRelatedData warn message",e);
                }
            }
        }else {
            throw new TicketFlowFrameException(BaseCode.PAY_TRADE_CHECK_ERROR);
        }
        return orderPayCheckVo;
    }
    
    
    /**
     * 支付宝异步通知处理。
     * 手动 ReentrantLock（而非 @ServiceLock）：先加锁（订单号已知），
     * 锁内调 payClient.notify() 验签 + 幂等，再执行退款 / updateOrderRelatedData。
     * 如果订单已取消 → 自动退款（延迟订单关闭场景）。
     *
     * ALIPAY_NOTIFY_SUCCESS_RESULT = "success"（支付宝要求的明文返回）
     */
    public String alipayNotify(HttpServletRequest request){

        Map<String, String> params = new HashMap<>(256);
        if (request instanceof final CustomizeRequestWrapper customizeRequestWrapper) {
            String requestBody = customizeRequestWrapper.getRequestBody();
            params = StringUtil.convertQueryStringToMap(requestBody);
        }
        log.info("收到支付宝回调通知 params : {}",JSON.toJSONString(params));
        String outTradeNo = params.get("out_trade_no");
        if (StringUtil.isEmpty(outTradeNo)) {
            return "failure";
        }

        RLock lock = serviceLockTool.getLock(LockType.Reentrant, UPDATE_ORDER_STATUS_LOCK,
                new String[]{outTradeNo});
        lock.lock();
        try {
            Order order = orderMapper.selectOne(Wrappers.lambdaQuery(Order.class).eq(Order::getOrderNumber, Long.parseLong(outTradeNo)));
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
                        || !ALIPAY_NOTIFY_SUCCESS_RESULT.equals(notifyResponse.getData().getPayResult())) {
                    // 对账未确认支付（验签失败/金额不符/状态异常）：按失败应答让支付宝重试
                    log.error("支付宝回调对账失败，暂不退款 dto : {} response : {}",
                            JSON.toJSONString(notifyDto), JSON.toJSONString(notifyResponse));
                    return ALIPAY_NOTIFY_FAILURE_RESULT;
                }
                RefundDto refundDto = new RefundDto();
                refundDto.setOrderNumber(outTradeNo);
                refundDto.setAmount(order.getOrderPrice());
                refundDto.setChannel("alipay");
                refundDto.setReason("延迟订单关闭");
                ApiResponse<String> response = payClient.refund(refundDto);
                if (response.getCode().equals(BaseCode.SUCCESS.getCode())) {
                    Order updateOrder = new Order();
                    updateOrder.setEditTime(DateUtils.now());
                    updateOrder.setOrderStatus(OrderStatus.REFUND.getCode());
                    orderMapper.update(updateOrder,Wrappers.lambdaUpdate(Order.class).eq(Order::getOrderNumber, outTradeNo));
                    return ALIPAY_NOTIFY_SUCCESS_RESULT;
                }else {
                    log.error("pay服务退款失败 dto : {} response : {}",JSON.toJSONString(refundDto),JSON.toJSONString(response));
                    // 退款失败返回 failure，让支付宝按重试周期继续回调，重试期间再次发起退款
                    return ALIPAY_NOTIFY_FAILURE_RESULT;
                }
            }


            NotifyDto notifyDto = new NotifyDto();
            notifyDto.setChannel(PayChannel.ALIPAY.getValue());
            notifyDto.setParams(params);
            ApiResponse<NotifyVo> notifyResponse = payClient.notify(notifyDto);
            if (!Objects.equals(notifyResponse.getCode(), BaseCode.SUCCESS.getCode())) {
                throw new TicketFlowFrameException(notifyResponse);
            }
            if (ALIPAY_NOTIFY_SUCCESS_RESULT.equals(notifyResponse.getData().getPayResult())) {
                try {
                    orderService.updateOrderRelatedData(Long.parseLong(notifyResponse.getData().getOutTradeNo())
                            ,OrderStatus.PAY);
                }catch (Exception e) {
                    log.warn("updateOrderRelatedData warn message",e);
                }
            }
            return notifyResponse.getData().getPayResult();
        }finally {
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
        if (!WX_NOTIFY_SUCCESS_RESULT.equals(notifyVo.getPayResult())) {
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
        lock.lock();
        try {
            Order order = orderMapper.selectOne(Wrappers.lambdaQuery(Order.class)
                    .eq(Order::getOrderNumber, orderNumber));
            if (Objects.isNull(order)) {
                throw new TicketFlowFrameException(BaseCode.ORDER_NOT_EXIST);
            }
            // 订单已取消：自动退款（延迟订单关闭场景）
            if (Objects.equals(order.getOrderStatus(), OrderStatus.CANCEL.getCode())) {
                RefundDto refundDto = new RefundDto();
                refundDto.setOrderNumber(outTradeNo);
                refundDto.setAmount(order.getOrderPrice());
                refundDto.setChannel(PayChannel.WX.getValue());
                refundDto.setReason("延迟订单关闭");
                ApiResponse<String> response = payClient.refund(refundDto);
                if (response.getCode().equals(BaseCode.SUCCESS.getCode())) {
                    Order updateOrder = new Order();
                    updateOrder.setEditTime(DateUtils.now());
                    updateOrder.setOrderStatus(OrderStatus.REFUND.getCode());
                    orderMapper.update(updateOrder, Wrappers.lambdaUpdate(Order.class)
                            .eq(Order::getOrderNumber, orderNumber));
                } else {
                    // 退款失败返回 FAIL，微信会重试回调；返回 SUCCESS 会导致退款永久丢失
                    log.error("pay服务退款失败 dto : {} response : {}", JSON.toJSONString(refundDto), JSON.toJSONString(response));
                    return WX_NOTIFY_FAILURE_RESULT;
                }
                return WX_NOTIFY_SUCCESS_RESULT;
            }
            try {
                orderService.updateOrderRelatedData(orderNumber, OrderStatus.PAY);
            } catch (Exception e) {
                log.warn("updateOrderRelatedData warn message", e);
            }
            return WX_NOTIFY_SUCCESS_RESULT;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 支付/取消的核心 6 步编排：
     * 1. 校验 orderStatus（仅 CANCEL / PAY）
     * 2. 更新 Order status
     * 3. 更新 OrderTicketUser status
     * 4. 写入流水 OrderTicketUserRecord（CHANGE_STATUS / INCREASE）
     * 5. 取消时 Redis 扣减 accountOrderCount
     * 6. 调用 Lua（V1-V3）或 Lua + Feign（V4）操作座位和库存缓存
     *
     * PS：V4 路径走 Feign（programClient.operateProgramData → DB update），
     * V1-V3 路径靠延迟队列 DelayOperateProgramDataSend 异步更新 DB。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateOrderRelatedData(Long orderNumber,OrderStatus orderStatus){
        if (!(Objects.equals(orderStatus.getCode(), OrderStatus.CANCEL.getCode()) ||
                Objects.equals(orderStatus.getCode(), OrderStatus.PAY.getCode()))) {
            throw new TicketFlowFrameException(  BaseCode.OPERATE_ORDER_STATUS_NOT_PERMIT);
        }
        LambdaQueryWrapper<Order> orderLambdaQueryWrapper =
                Wrappers.lambdaQuery(Order.class).eq(Order::getOrderNumber, orderNumber);
        Order order = orderMapper.selectOne(orderLambdaQueryWrapper);
        checkOrderStatus(order);
        LambdaQueryWrapper<OrderTicketUser> orderTicketUserLambdaQueryWrapper =
                Wrappers.lambdaQuery(OrderTicketUser.class).eq(OrderTicketUser::getOrderNumber, order.getOrderNumber());
        List<OrderTicketUser> orderTicketUserList = orderTicketUserMapper.selectList(orderTicketUserLambdaQueryWrapper);
        if (CollectionUtil.isEmpty(orderTicketUserList)) {
            throw new TicketFlowFrameException(BaseCode.TICKET_USER_ORDER_NOT_EXIST);
        }
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
            recordTypeCode = RecordType.INCREASE.getCode();
            recordTypeValue = RecordType.INCREASE.getValue();
        }
        LambdaUpdateWrapper<Order> orderLambdaUpdateWrapper =
                Wrappers.lambdaUpdate(Order.class).eq(Order::getOrderNumber, order.getOrderNumber());
        int updateOrderResult = orderMapper.update(updateOrder,orderLambdaUpdateWrapper);
        LambdaUpdateWrapper<OrderTicketUser> orderTicketUserLambdaUpdateWrapper =
                Wrappers.lambdaUpdate(OrderTicketUser.class).eq(OrderTicketUser::getOrderNumber, order.getOrderNumber());
        int updateTicketUserOrderResult =
                orderTicketUserMapper.update(updateOrderTicketUser,orderTicketUserLambdaUpdateWrapper);
        if (updateOrderResult <= 0 || updateTicketUserOrderResult <= 0) {
            throw new TicketFlowFrameException(BaseCode.ORDER_CANAL_ERROR);
        }
        List<SeatIdAndTicketUserIdDomain> seatIdAndTicketUserIdDomainList = new ArrayList<>();
        List<OrderTicketUserRecord> orderTicketUserRecordList = new ArrayList<>();
        for (OrderTicketUser orderTicketUser : orderTicketUserList) {
            OrderTicketUserRecord orderTicketUserRecord = new OrderTicketUserRecord();
            BeanUtils.copyProperties(orderTicketUser,orderTicketUserRecord);
            orderTicketUserRecord.setId(uidGenerator.getUid());
            orderTicketUserRecord.setIdentifierId(order.getIdentifierId());
            orderTicketUserRecord.setTicketUserOrderId(orderTicketUser.getId());
            orderTicketUserRecord.setRecordTypeCode(recordTypeCode);
            orderTicketUserRecord.setRecordTypeValue(recordTypeValue);
            orderTicketUserRecordList.add(orderTicketUserRecord);
            seatIdAndTicketUserIdDomainList.add(new SeatIdAndTicketUserIdDomain(orderTicketUser.getSeatId(),
                    orderTicketUser.getTicketUserId()));
        }
        orderTicketUserRecordService.saveBatch(orderTicketUserRecordList);

        if (Objects.equals(orderStatus.getCode(), OrderStatus.CANCEL.getCode())) {
            redisCache.incrBy(RedisKeyBuild.createRedisKey(
                    RedisKeyManage.ACCOUNT_ORDER_COUNT,order.getUserId(),order.getProgramId()),-updateTicketUserOrderResult);
        }
        Long programId = order.getProgramId();
        Map<Long, List<OrderTicketUser>> orderTicketUserSeatList =
                orderTicketUserList.stream().collect(Collectors.groupingBy(OrderTicketUser::getTicketCategoryId));
        Map<Long,List<Long>> seatMap = new HashMap<>(orderTicketUserSeatList.size());
        orderTicketUserSeatList.forEach((k,v) -> {
            seatMap.put(k,v.stream().map(OrderTicketUser::getSeatId).collect(Collectors.toList()));
        });
        updateProgramRelatedDataResolution(programId,seatMap,orderStatus,order.getIdentifierId(),order.getUserId(),
                seatIdAndTicketUserIdDomainList,order.getOrderVersion());
    }

    public void checkOrderStatus(Order order){
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
     *   data[0] = unLockSeatIdjsonArray   —— 从 LOCK hash 删除的座位
     *   data[1] = addSeatDatajsonArray     —— 写入 NO_SOLD / SOLD hash 的座位
     *   data[2] = jsonArray                —— 调整余票数量（cancel 加回 / pay 扣减）
     *   data[3] = seatIdAndTicketUserIdDomainList —— 购票人关联记录
     * V4 路径额外走 Feign 调用 operateProgramData 更新 DB（DB 状态统一切换）。
     * @param orderVersion 见 ProgramOrderVersion：V1=1, V2=2, V21=21, V3=3, V31=31, V4=4, V41=41
     */
    public void updateProgramRelatedDataResolution(Long programId,Map<Long,List<Long>> seatMap, OrderStatus orderStatus,Long identifierId, Long userId,
                                                   List<SeatIdAndTicketUserIdDomain> seatIdAndTicketUserIdDomainList,
                                                   Integer orderVersion){
        Map<Long, List<SeatVo>> seatVoMap = new HashMap<>(seatMap.size());
        seatMap.forEach((k,v) -> {
            seatVoMap.put(k,redisCache.multiGetForHash(
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
        seatVoMap.forEach((k,v) -> {
            JSONObject unLockSeatIdjsonObject = new JSONObject();
            unLockSeatIdjsonObject.put("programSeatLockHashKey", RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, programId, k).getRelKey());
            unLockSeatIdjsonObject.put("unLockSeatIdList",v.stream()
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
            }else if (Objects.equals(orderStatus.getCode(), OrderStatus.PAY.getCode())) {
                seatHashKeyAdd = RedisKeyBuild.createRedisKey(
                        RedisKeyManage.PROGRAM_SEAT_SOLD_RESOLUTION_HASH, programId, k).getRelKey();
                for (SeatVo seatVo : v) {
                    seatVo.setSellStatus(SellStatus.SOLD.getCode());
                }
            }
            seatDatajsonObject.put("seatHashKeyAdd",seatHashKeyAdd);
            List<String> seatDataList = new ArrayList<>();
            for (SeatVo seatVo : v) {
                seatDataList.add(String.valueOf(seatVo.getId()));
                seatDataList.add(JSON.toJSONString(seatVo));
            }
            seatDatajsonObject.put("seatDataList",seatDataList);
            addSeatDatajsonArray.add(seatDatajsonObject);
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("programTicketRemainNumberHashKey",RedisKeyBuild.createRedisKey(
                    RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION, programId, k).getRelKey());
            jsonObject.put("ticketCategoryId",String.valueOf(k));
            jsonObject.put("count",v.size());
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
        //如果创建订单版本是v1，v2，v3
        if (!orderVersion.equals(ProgramOrderVersion.V4_VERSION.getValue())){
            orderProgramCacheResolutionOperate.programCacheReverseOperate(keys,data);
            if (Objects.equals(orderStatus.getCode(), OrderStatus.PAY.getCode())) {
                programOperateDataDto.setSellStatus(SellStatus.SOLD.getCode());
                delayOperateProgramDataSend.sendMessage(JSON.toJSONString(programOperateDataDto));
            }
        }else {
            //如果创建订单版本是v4 更新节目服务的相关数据
            if (Objects.equals(orderStatus.getCode(), OrderStatus.PAY.getCode()) ||
                    Objects.equals(orderStatus.getCode(), OrderStatus.CANCEL.getCode())) {
                programOperateDataDto.setSellStatus(Objects.equals(orderStatus.getCode(), OrderStatus.PAY.getCode()) ? SellStatus.SOLD.getCode() : SellStatus.NO_SOLD.getCode());
                ApiResponse<Boolean> programApiResponse = programClient.operateProgramData(programOperateDataDto);
                if (!Objects.equals(programApiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
                    throw new TicketFlowFrameException(programApiResponse);
                }
            }
            orderProgramCacheResolutionOperate.programCacheReverseOperate(keys,data);
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
        BeanUtil.copyProperties(order,orderGetVo);
        
        List<OrderTicketInfoVo> orderTicketInfoVoList = new ArrayList<>();
        Map<BigDecimal, List<OrderTicketUser>> orderTicketUserMap = 
                orderTicketUserList.stream().collect(Collectors.groupingBy(OrderTicketUser::getOrderPrice));
        orderTicketUserMap.forEach((k,v) -> {
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
                    .reduce(BigDecimal.ZERO,BigDecimal::add));
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
        List<TicketUserVo> filterTicketUserVoList = new ArrayList<>();
        Map<Long, TicketUserVo> ticketUserVoMap = userAndTicketUserListVo.getTicketUserVoList()
                .stream().collect(Collectors.toMap(TicketUserVo::getId, ticketUserVo -> ticketUserVo, (v1, v2) -> v2));
        for (OrderTicketUser orderTicketUser : orderTicketUserList) {
            filterTicketUserVoList.add(ticketUserVoMap.get(orderTicketUser.getTicketUserId()));
        }
        UserInfoVo userInfoVo = new UserInfoVo();
        BeanUtil.copyProperties(userAndTicketUserListVo.getUserVo(),userInfoVo);
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
    @RepeatExecuteLimit(name = CREATE_PROGRAM_ORDER_MQ,keys = {"#orderCreateMq.orderNumber"})
    @Transactional(rollbackFor = Exception.class)
    public String createMq(OrderCreateMq orderCreateMq){
        List<OrderTicketUserCreateDto> orderTicketUserCreateDtoList = orderCreateMq.getOrderTicketUserCreateDtoList();
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
            //将因为修改节目服务余票和座位失败，导致丢弃的订单放入redis中
            redisCache.leftPushForList(RedisKeyBuild.createRedisKey(RedisKeyManage.DISCARD_ORDER,
                    orderCreateMq.getProgramId()),new DiscardOrder(orderCreateMq, DiscardOrderReason.MODIFY_PROGRAM_REMAIN_NUMBER_SEAT_FAIL.getCode()));
            throw new TicketFlowFrameException(programApiResponse);
        }
        //真正地创建订单
        String orderNumber = createByMq(orderCreateMq);
        redisCache.set(RedisKeyBuild.createRedisKey(RedisKeyManage.ORDER_MQ,orderNumber),orderNumber,1, TimeUnit.MINUTES);
        return orderNumber;
    }
    
    public String getCache(OrderGetDto orderGetDto) {
        return redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.ORDER_MQ,orderGetDto.getOrderNumber()),String.class);
    }
    
    @RepeatExecuteLimit(name = CANCEL_PROGRAM_ORDER,keys = {"#orderCancelDto.orderNumber"})
    @ServiceLock(name = UPDATE_ORDER_STATUS_LOCK,keys = {"#orderCancelDto.orderNumber"})
    @Transactional(rollbackFor = Exception.class)
    public boolean initiateCancel(OrderCancelDto orderCancelDto){
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
    
    
    public void delOrderAndOrderTicketUser(){
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
                        .eq(Objects.nonNull(orderSimpleListDto.getOrderNumber()),Order::getOrderNumber, orderSimpleListDto.getOrderNumber())
                        .eq(Objects.nonNull(orderSimpleListDto.getUserId()),Order::getUserId, orderSimpleListDto.getUserId())
                        .orderByDesc(Order::getCreateOrderTime);
        List<Order> orderList = orderMapper.selectList(orderLambdaQueryWrapper);
        if (CollectionUtil.isEmpty(orderList)) {
            return orderListVos;
        }
        return BeanUtil.copyToList(orderList, OrderListVo.class);
    }
}
