package com.ticketflow.service;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.fastjson.JSON;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ticketflow.dto.NotifyDto;
import com.ticketflow.dto.PayBillDto;
import com.ticketflow.dto.PayDto;
import com.ticketflow.dto.RefundDto;
import com.ticketflow.dto.RefundStatusDto;
import com.ticketflow.dto.TradeCheckDto;
import com.ticketflow.entity.PayBill;
import com.ticketflow.entity.RefundBill;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.PayBillStatus;
import com.ticketflow.enums.PayChannel;
import com.ticketflow.enums.RefundStatus;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.observability.BusinessMetrics;
import com.ticketflow.observability.Metrics;
import com.ticketflow.mapper.PayBillMapper;
import com.ticketflow.mapper.RefundBillMapper;
import com.ticketflow.pay.PayResult;
import com.ticketflow.pay.PayStrategyContext;
import com.ticketflow.pay.PayStrategyHandler;
import com.ticketflow.pay.RefundResult;
import com.ticketflow.pay.TradeResult;
import com.ticketflow.servicelock.annotion.ServiceLock;
import com.ticketflow.util.DateUtils;
import com.ticketflow.vo.NotifyVo;
import com.ticketflow.vo.PayBillVo;
import com.ticketflow.vo.RefundStatusVo;
import io.micrometer.core.instrument.MeterRegistry;
import com.ticketflow.vo.TradeCheckVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.ticketflow.constant.Constant.ALIPAY_NOTIFY_FAILURE_RESULT;
import static com.ticketflow.constant.Constant.ALIPAY_NOTIFY_SUCCESS_RESULT;
import static com.ticketflow.constant.Constant.WX_NOTIFY_FAILURE_RESULT;
import static com.ticketflow.constant.Constant.WX_NOTIFY_SUCCESS_RESULT;
import static com.ticketflow.core.DistributedLockConstants.COMMON_PAY;
import static com.ticketflow.core.DistributedLockConstants.TRADE_CHECK;

/**
 * 支付核心服务——统一支付/退款/对账入口。
 * <p>
 * 委托 PayStrategyHandler（目前为 AlipayStrategyHandler）完成实际支付，
 * 处理支付宝异步通知校验与账单状态流转
 */
@Slf4j
@Service
public class PayService {

    @Autowired
    private PayBillMapper payBillMapper;

    @Autowired
    private RefundBillMapper refundBillMapper;

    @Autowired
    private PayStrategyContext payStrategyContext;

    @Autowired
    private UidGenerator uidGenerator;

    /**
     * 显式事务模板：渠道 HTTP 调用（支付/退款）刻意放在事务外，避免远程调用期间占用 DB 连接与拉长锁持有时间；
     * 只有本地账单写操作放进 TransactionTemplate。
     */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * 退款受理/确认指标（registy 缺失时 {@link BusinessMetrics} 静默降级，不影响交易主流程）
     */
    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    /**
     * 通用支付，用订单号加锁防止多次支付成功，不依赖第三方支付的幂等性
     *
     */
    @ServiceLock(name = COMMON_PAY, keys = {"#payDto.orderNumber"})
    public String commonPay(PayDto payDto) {
        // 查询已有账单：非 NO_PAY 状态表示已支付/已取消/已退款，拒绝重复支付
        LambdaQueryWrapper<PayBill> payBillLambdaQueryWrapper =
                Wrappers.lambdaQuery(PayBill.class).eq(PayBill::getOutOrderNo, payDto.getOrderNumber());
        PayBill payBill = payBillMapper.selectOne(payBillLambdaQueryWrapper);
        if (Objects.nonNull(payBill) && !Objects.equals(payBill.getPayBillStatus(), PayBillStatus.NO_PAY.getCode())) {
            throw new TicketFlowFrameException(BaseCode.PAY_BILL_IS_NOT_NO_PAY);
        }
        // 委托支付渠道（当前为支付宝）执行支付，返回支付表单/URL。HTTP 调用放在事务外。
        PayStrategyHandler payStrategyHandler = payStrategyContext.get(payDto.getChannel());
        PayResult pay = payStrategyHandler.pay(String.valueOf(payDto.getOrderNumber()), payDto.getPrice(),
                payDto.getSubject(), payDto.getNotifyUrl(), payDto.getReturnUrl());
        if (pay.isSuccess()) {
            final boolean firstPay = Objects.isNull(payBill);
            final PayBill existBill = payBill;
            transactionTemplate.executeWithoutResult(status -> {
                if (firstPay) {
                    // 首次支付：插入新账单记录（状态为 NO_PAY，等待异步通知确认）
                    PayBill newBill = new PayBill();
                    newBill.setId(uidGenerator.getUid());
                    newBill.setOutOrderNo(String.valueOf(payDto.getOrderNumber()));
                    newBill.setPayChannel(payDto.getChannel());
                    newBill.setPayScene("生产");
                    newBill.setSubject(payDto.getSubject());
                    newBill.setPayAmount(payDto.getPrice());
                    newBill.setPayBillType(payDto.getPayBillType());
                    newBill.setPayBillStatus(PayBillStatus.NO_PAY.getCode());
                    newBill.setPayTime(DateUtils.now());
                    payBillMapper.insert(newBill);
                } else {
                    // 重复调起支付：仅更新支付时间（非幂等重试，而是重新生成支付链接）
                    PayBill updatePayBill = new PayBill();
                    updatePayBill.setId(existBill.getId());
                    updatePayBill.setPayTime(DateUtils.now());
                    payBillMapper.updateById(updatePayBill);
                }
            });
        }

        return pay.getBody();
    }

    @Transactional(rollbackFor = Exception.class)
    public NotifyVo notify(NotifyDto notifyDto) {
        NotifyVo notifyVo = new NotifyVo();
        log.info("回调通知参数 ===> {}", JSON.toJSONString(notifyDto));
        Map<String, String> params = notifyDto.getParams();
        // 微信回调应答为 SUCCESS/FAIL（大小写敏感），支付宝为 success/failure
        boolean isWxChannel = PayChannel.WX.getValue().equals(notifyDto.getChannel());
        String successResult = isWxChannel ? WX_NOTIFY_SUCCESS_RESULT : ALIPAY_NOTIFY_SUCCESS_RESULT;
        String failureResult = isWxChannel ? WX_NOTIFY_FAILURE_RESULT : ALIPAY_NOTIFY_FAILURE_RESULT;

        // 第一步：签名验证（支付宝异步通知自带 sign 字段，验证报文真实性）
        PayStrategyHandler payStrategyHandler = payStrategyContext.get(notifyDto.getChannel());
        boolean signVerifyResult = payStrategyHandler.signVerify(params);
        if (!signVerifyResult) {
            notifyVo.setPayResult(failureResult);
            return notifyVo;
        }
        // 第二步：查询本地账单
        LambdaQueryWrapper<PayBill> payBillLambdaQueryWrapper =
                Wrappers.lambdaQuery(PayBill.class).eq(PayBill::getOutOrderNo, params.get("out_trade_no"));
        PayBill payBill = payBillMapper.selectOne(payBillLambdaQueryWrapper);
        if (Objects.isNull(payBill)) {
            log.error("账单为空 notifyDto : {}", JSON.toJSONString(notifyDto));
            notifyVo.setPayResult(failureResult);
            return notifyVo;
        }
        // 第三步：状态机——已支付/已取消/已退款的账单直接返回成功（避免重复处理导致状态回退）
        if (Objects.equals(payBill.getPayBillStatus(), PayBillStatus.PAY.getCode())) {
            log.info("账单已支付 notifyDto : {}", JSON.toJSONString(notifyDto));
            notifyVo.setOutTradeNo(payBill.getOutOrderNo());
            notifyVo.setPayResult(successResult);
            return notifyVo;
        }
        if (Objects.equals(payBill.getPayBillStatus(), PayBillStatus.CANCEL.getCode())) {
            log.info("账单已取消 notifyDto : {}", JSON.toJSONString(notifyDto));
            notifyVo.setOutTradeNo(payBill.getOutOrderNo());
            notifyVo.setPayResult(successResult);
            return notifyVo;
        }
        if (Objects.equals(payBill.getPayBillStatus(), PayBillStatus.REFUND.getCode())) {
            log.info("账单已退单 notifyDto : {}", JSON.toJSONString(notifyDto));
            notifyVo.setOutTradeNo(payBill.getOutOrderNo());
            notifyVo.setPayResult(successResult);
            return notifyVo;
        }
        // 第四步：数据验证（比较支付通知的金额与本地账单金额是否一致）
        boolean dataVerify = payStrategyHandler.dataVerify(notifyDto.getParams(), payBill);
        if (!dataVerify) {
            notifyVo.setPayResult(failureResult);
            return notifyVo;
        }
        // 第五步：状态流转 NO_PAY → PAY（条件更新，0 行说明并发已被其他请求先更新，
        // 按失败应答让渠道重试，避免重复处理）
        PayBill updatePayBill = new PayBill();
        updatePayBill.setPayBillStatus(PayBillStatus.PAY.getCode());
        updatePayBill.setPayTime(DateUtils.now());
        LambdaUpdateWrapper<PayBill> payBillLambdaUpdateWrapper =
                Wrappers.lambdaUpdate(PayBill.class)
                        .eq(PayBill::getOutOrderNo, params.get("out_trade_no"))
                        .eq(PayBill::getPayBillStatus, PayBillStatus.NO_PAY.getCode());
        int updateCount = payBillMapper.update(updatePayBill, payBillLambdaUpdateWrapper);
        if (updateCount == 0) {
            log.warn("回调并发或状态已流转，未更新账单 notifyDto : {}", JSON.toJSONString(notifyDto));
            notifyVo.setPayResult(failureResult);
            return notifyVo;
        }
        notifyVo.setOutTradeNo(payBill.getOutOrderNo());
        notifyVo.setPayResult(successResult);
        return notifyVo;
    }

    @ServiceLock(name = TRADE_CHECK, keys = {"#tradeCheckDto.outTradeNo"})
    public TradeCheckVo tradeCheck(TradeCheckDto tradeCheckDto) {
        TradeCheckVo tradeCheckVo = new TradeCheckVo();
        // 查询支付渠道（支付宝/微信）的交易状态和金额：公网 HTTP 调用放在事务外，避免占用 DB 连接
        PayStrategyHandler payStrategyHandler = payStrategyContext.get(tradeCheckDto.getChannel());
        TradeResult tradeResult = payStrategyHandler.queryTrade(tradeCheckDto.getOutTradeNo());
        BeanUtil.copyProperties(tradeResult, tradeCheckVo);
        if (!tradeResult.isSuccess()) {
            return tradeCheckVo;
        }
        BigDecimal totalAmount = tradeResult.getTotalAmount();
        String outTradeNo = tradeResult.getOutTradeNo();
        Integer payBillStatus = tradeResult.getPayBillStatus();
        // 比对本地账单与支付渠道的金额（不一致说明数据异常）
        LambdaQueryWrapper<PayBill> payBillLambdaQueryWrapper =
                Wrappers.lambdaQuery(PayBill.class).eq(PayBill::getOutOrderNo, outTradeNo);
        PayBill payBill = payBillMapper.selectOne(payBillLambdaQueryWrapper);
        if (Objects.isNull(payBill)) {
            log.error("账单为空 tradeCheckDto : {}", JSON.toJSONString(tradeCheckDto));
            tradeCheckVo.setSuccess(false);
            return tradeCheckVo;
        }
        if (payBill.getPayAmount().compareTo(totalAmount) != 0) {
            log.error("支付渠道 和库中账单支付金额不一致 支付渠道支付金额 : {}, 库中账单支付金额 : {}, tradeCheckDto : {}",
                    totalAmount, payBill.getPayAmount(), JSON.toJSONString(tradeCheckDto));
            tradeCheckVo.setSuccess(false);
            return tradeCheckVo;
        }
        // 状态不一致时以支付渠道为准，同步更新本地账单状态
        if (!Objects.equals(payBill.getPayBillStatus(), payBillStatus)) {
            // 终态保护：账单已退单/已取消时不允许被渠道轮询结果回退。
            // 典型场景：支付宝退款后 trade_status 仍是 TRADE_SUCCESS，会被映射成 PAY，
            // 若直接覆盖则 REFUND 被改回 PAY，重新打开退款入口导致二次退款。
            if (Objects.equals(payBill.getPayBillStatus(), PayBillStatus.REFUND.getCode())
                    || Objects.equals(payBill.getPayBillStatus(), PayBillStatus.CANCEL.getCode())) {
                log.warn("账单已处于终态({})，忽略渠道状态({})回写 tradeCheckDto : {}",
                        payBill.getPayBillStatus(), payBillStatus, JSON.toJSONString(tradeCheckDto));
                tradeCheckVo.setPayBillStatus(payBill.getPayBillStatus());
                return tradeCheckVo;
            }
            log.warn("支付渠道和库中账单交易状态不一致 支付渠道payBillStatus : {}, 库中payBillStatus : {}, tradeCheckDto : {}",
                    payBillStatus, payBill.getPayBillStatus(), JSON.toJSONString(tradeCheckDto));
            transactionTemplate.executeWithoutResult(status -> {
                PayBill updatePayBill = new PayBill();
                updatePayBill.setId(payBill.getId());
                updatePayBill.setPayBillStatus(payBillStatus);
                LambdaUpdateWrapper<PayBill> payBillLambdaUpdateWrapper =
                        Wrappers.lambdaUpdate(PayBill.class).eq(PayBill::getOutOrderNo, outTradeNo);
                payBillMapper.update(updatePayBill, payBillLambdaUpdateWrapper);
            });
            return tradeCheckVo;
        }
        return tradeCheckVo;
    }

    @ServiceLock(name = COMMON_PAY, keys = {"#refundDto.orderNumber"})
    public String refund(RefundDto refundDto) {
        // 校验：账单存在 → 账单已支付 → 累计已退+本次退款不超过支付金额（支持部分退款）
        PayBill payBill = payBillMapper.selectOne(Wrappers.lambdaQuery(PayBill.class)
                .eq(PayBill::getOutOrderNo, refundDto.getOrderNumber()));
        if (Objects.isNull(payBill)) {
            throw new TicketFlowFrameException(BaseCode.PAY_BILL_NOT_EXIST);
        }

        if (!Objects.equals(payBill.getPayBillStatus(), PayBillStatus.PAY.getCode())) {
            throw new TicketFlowFrameException(BaseCode.PAY_BILL_IS_NOT_PAY_STATUS);
        }
        // 累计已退金额（含「受理待确认」与「处理中」的退款单，不含退款失败终态单），防止超退。
        // 0 态的钱可能已经退出去了，必须算进累计，否则并发/重试下能退超支付金额
        List<RefundBill> refundBillList = refundBillMapper.selectList(
                Wrappers.lambdaQuery(RefundBill.class)
                        .eq(RefundBill::getOutOrderNo, refundDto.getOrderNumber())
                        .eq(RefundBill::getStatus, 1)
                        .in(RefundBill::getRefundStatus,
                                RefundStatus.REFUND_ACCEPT.getCode(),
                                RefundStatus.REFUND_PROCESSING.getCode(),
                                RefundStatus.REFUND_SUCCESS.getCode()));
        BigDecimal refundedAmount = refundBillList.stream().map(RefundBill::getRefundAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (refundedAmount.add(refundDto.getAmount()).compareTo(payBill.getPayAmount()) > 0) {
            throw new TicketFlowFrameException(BaseCode.REFUND_AMOUNT_GREATER_THAN_PAY_AMOUNT);
        }

        // 退款单号：优先用调用方传入的幂等键（同一退款意图重试复用同一单号），
        // 作为渠道侧幂等键（out_request_no）与 d_refund_bill.out_refund_no 唯一键；
        // 未提供时退化为每次生成新单号（旧行为，不幂等）。
        String outRefundNo = (Objects.isNull(refundDto.getRefundRequestId())
                || refundDto.getRefundRequestId().isBlank())
                ? String.valueOf(uidGenerator.getUid())
                : refundDto.getRefundRequestId();
        // 同一幂等键已受理过：按退款单状态续做 —— 已成功/处理中直接返回，
        // 受理待确认（0）只查渠道不重发，失败（3）允许用同一幂等键重入
        RefundBill existRefund = refundBillMapper.selectOne(
                Wrappers.lambdaQuery(RefundBill.class).eq(RefundBill::getOutRefundNo, outRefundNo));
        if (Objects.nonNull(existRefund)) {
            return resumeRefund(existRefund, refundDto, payBill);
        }
        // 先落「受理待确认」退款单，再调渠道：钱一旦出去，系统里必须先有这条记录。
        // 旧顺序是先调渠道再落库，落库失败就是「渠道退了款、本地无退款单」，
        // 退款确认任务永远看不到这笔钱（P0-10 孤儿退款）
        RefundBill acceptRefundBill = buildAcceptRefundBill(refundDto, payBill, outRefundNo);
        try {
            transactionTemplate.executeWithoutResult(status -> refundBillMapper.insert(acceptRefundBill));
        } catch (DuplicateKeyException e) {
            // 并发下同一幂等键落库：按已存在的那条退款单续做，渠道侧同单号本身也是幂等的
            RefundBill concurrentRefund = refundBillMapper.selectOne(
                    Wrappers.lambdaQuery(RefundBill.class).eq(RefundBill::getOutRefundNo, outRefundNo));
            if (Objects.isNull(concurrentRefund)) {
                log.error("退款受理单落库冲突但查不到记录 outRefundNo : {}", outRefundNo, e);
                throw new TicketFlowFrameException(BaseCode.REFUND_ERROR);
            }
            log.warn("退款单已存在（并发重复），按已有状态续做 outRefundNo : {}", outRefundNo);
            return resumeRefund(concurrentRefund, refundDto, payBill);
        }
        BusinessMetrics.increment(meterRegistry, Metrics.PAY_REFUND_TOTAL,
                Metrics.RESULT, Metrics.RESULT_ACCEPTED);
        return callChannelRefund(acceptRefundBill, refundDto, payBill);
    }

    /**
     * 同一幂等键（outRefundNo）再次请求退款时，按退款单已有状态续做。
     * <p>
     * 四种状态的处理各不相同，这里的取舍是「宁可让调用方重试，也不能误判资金结果」：
     * <ul>
     *   <li><b>2 已退款</b>、<b>1 处理中</b>：渠道已受理，直接返回单号；</li>
     *   <li><b>0 受理待确认</b>：上一次已经把退款发出去了却没拿到结果，只<b>查</b>不重发——
     *       查不到明确结果就继续挂着（不在本地编造成功，也不编造失败，钱可能已经退出去了）；</li>
     *   <li><b>3 退款失败</b>：渠道明确没退成，允许用同一幂等键重入（重置为 0 后重新发起）。</li>
     * </ul>
     */
    private String resumeRefund(RefundBill existRefund, RefundDto refundDto, PayBill payBill) {
        String outRefundNo = existRefund.getOutRefundNo();
        RefundStatus refundStatus = RefundStatus.getRc(existRefund.getRefundStatus());
        if (Objects.isNull(refundStatus)) {
            log.error("退款单状态非法 outRefundNo : {} refundStatus : {}",
                    outRefundNo, existRefund.getRefundStatus());
            throw new TicketFlowFrameException(BaseCode.REFUND_ERROR);
        }
        switch (refundStatus) {
            case REFUND_SUCCESS:
            case REFUND_PROCESSING:
                log.info("退款幂等命中（{}），直接返回 outRefundNo : {}", refundStatus.getMsg(), outRefundNo);
                BusinessMetrics.increment(meterRegistry, Metrics.PAY_REFUND_TOTAL,
                        Metrics.RESULT, Metrics.RESULT_IDEMPOTENT);
                return outRefundNo;
            case REFUND_ACCEPT:
                // 只查不重发：同一退款意图绝不能因为「我们没拿到结果」而再发一次
                RefundResult queryResult = payStrategyContext.get(refundDto.getChannel())
                        .queryRefund(refundDto.getOrderNumber(), outRefundNo);
                if (queryResult.isSuccess() && Objects.nonNull(queryResult.getRefundStatus())) {
                    applyRefundResult(existRefund, queryResult.getRefundStatus(), payBill);
                    return outRefundNo;
                }
                log.error("退款结果仍未确认，保持受理态由确认任务继续查 outRefundNo : {} message : {}",
                        outRefundNo, queryResult.getMessage());
                BusinessMetrics.increment(meterRegistry, Metrics.PAY_REFUND_TOTAL,
                        Metrics.RESULT, Metrics.RESULT_UNKNOWN);
                throw new TicketFlowFrameException(BaseCode.REFUND_ERROR.getCode(),
                        "退款结果未确认，请稍后重试或等待渠道异步确认");
            case REFUND_FAIL:
                // 渠道明确失败 = 钱没退出去，允许重入：重置回受理态后用同一幂等键重新发起
                log.warn("退款此前确认失败，按同一幂等键重新发起 outRefundNo : {}", outRefundNo);
                markRefundStatus(existRefund.getId(), RefundStatus.REFUND_ACCEPT);
                return callChannelRefund(existRefund, refundDto, payBill);
            default:
                throw new TicketFlowFrameException(BaseCode.REFUND_ERROR);
        }
    }

    /**
     * 组装「受理待确认」退款单：退款动作还没发生，付款时间留空，
     * 渠道给出结果后再回填状态与退款时间。
     */
    private RefundBill buildAcceptRefundBill(RefundDto refundDto, PayBill payBill, String outRefundNo) {
        RefundBill refundBill = new RefundBill();
        refundBill.setId(uidGenerator.getUid());
        refundBill.setOutOrderNo(payBill.getOutOrderNo());
        refundBill.setPayBillId(payBill.getId());
        refundBill.setOutRefundNo(outRefundNo);
        refundBill.setRefundAmount(refundDto.getAmount());
        refundBill.setRefundStatus(RefundStatus.REFUND_ACCEPT.getCode());
        refundBill.setReason(refundDto.getReason());
        return refundBill;
    }

    /**
     * 调用渠道退款并回填结果。
     * <p>
     * 关键取舍：<b>渠道调用异常时退款单保持 0 态</b>。超时/网络异常下我们无法判断钱是否已经退出去，
     * 此时置「失败」等于把一笔可能成功的退款永久丢掉；置「成功」更是直接资损。
     * 保持 0 态交给 {@code RefundCheckTask} 查渠道确认，是本流程唯一安全的出口。
     */
    private String callChannelRefund(RefundBill refundBill, RefundDto refundDto, PayBill payBill) {
        PayStrategyHandler payStrategyHandler = payStrategyContext.get(refundDto.getChannel());
        RefundResult refundResult;
        try {
            refundResult = payStrategyHandler.refund(refundDto.getOrderNumber(), refundDto.getAmount(),
                    payBill.getPayAmount(), refundDto.getReason(), refundBill.getOutRefundNo());
        } catch (Exception e) {
            log.error("退款渠道调用异常，退款单保持受理待确认 outRefundNo : {}",
                    refundBill.getOutRefundNo(), e);
            BusinessMetrics.increment(meterRegistry, Metrics.PAY_REFUND_TOTAL,
                    Metrics.RESULT, Metrics.RESULT_UNKNOWN);
            if (e instanceof TicketFlowFrameException ticketFlowFrameException) {
                throw ticketFlowFrameException;
            }
            throw new TicketFlowFrameException(BaseCode.REFUND_ERROR);
        }
        if (!refundResult.isSuccess()) {
            // 渠道明确拒绝/关闭：确认没退出去，落失败终态（可用同一幂等键重入）
            markRefundStatus(refundBill.getId(), RefundStatus.REFUND_FAIL);
            log.error("退款渠道明确失败 outRefundNo : {} message : {}",
                    refundBill.getOutRefundNo(), refundResult.getMessage());
            BusinessMetrics.increment(meterRegistry, Metrics.PAY_REFUND_TOTAL,
                    Metrics.RESULT, Metrics.RESULT_FAIL);
            throw new TicketFlowFrameException(BaseCode.REFUND_ERROR.getCode(), refundResult.getMessage());
        }
        // 渠道没给状态时按「处理中」处理：等确认任务查渠道，不当成功
        Integer refundStatus = Objects.isNull(refundResult.getRefundStatus())
                ? RefundStatus.REFUND_PROCESSING.getCode() : refundResult.getRefundStatus();
        applyRefundResult(refundBill, refundStatus, payBill);
        return refundBill.getOutRefundNo();
    }

    /**
     * 回填渠道退款结果，并按「已确认到账」累计决定是否把支付账单置为已退款。
     * <p>
     * 账单置 REFUND 只统计 refund_status=2 的单：在途（0/1）单最终可能失败，
     * 若按含在途的累计判断，在途单失败后账单已置 REFUND 无法继续退款，会导致少退。
     */
    private void applyRefundResult(RefundBill refundBill, Integer refundStatus, PayBill payBill) {
        transactionTemplate.executeWithoutResult(status -> {
            RefundBill updateRefundBill = new RefundBill();
            updateRefundBill.setId(refundBill.getId());
            updateRefundBill.setRefundStatus(refundStatus);
            updateRefundBill.setRefundTime(DateUtils.now());
            refundBillMapper.updateById(updateRefundBill);
            if (!Objects.equals(refundStatus, RefundStatus.REFUND_SUCCESS.getCode())) {
                return;
            }
            BigDecimal confirmedRefundedAmount = refundBillMapper.selectList(
                            Wrappers.lambdaQuery(RefundBill.class)
                                    .eq(RefundBill::getOutOrderNo, payBill.getOutOrderNo())
                                    .eq(RefundBill::getStatus, 1)
                                    .eq(RefundBill::getRefundStatus, RefundStatus.REFUND_SUCCESS.getCode()))
                    .stream()
                    // 再按状态过滤一次：SQL 条件与内存口径必须一致，脏数据/在途单不能当已确认金额
                    .filter(bill -> Objects.equals(bill.getRefundStatus(), RefundStatus.REFUND_SUCCESS.getCode()))
                    .map(RefundBill::getRefundAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (confirmedRefundedAmount.compareTo(payBill.getPayAmount()) < 0) {
                return;
            }
            PayBill updatePayBill = new PayBill();
            updatePayBill.setPayBillStatus(PayBillStatus.REFUND.getCode());
            // 条件更新兜底并发：0 行说明账单状态已被其他流程更新，不覆盖
            payBillMapper.update(updatePayBill, Wrappers.lambdaUpdate(PayBill.class)
                    .eq(PayBill::getOutOrderNo, payBill.getOutOrderNo())
                    .eq(PayBill::getPayBillStatus, PayBillStatus.PAY.getCode()));
        });
        if (Objects.equals(refundStatus, RefundStatus.REFUND_SUCCESS.getCode())) {
            BusinessMetrics.increment(meterRegistry, Metrics.PAY_REFUND_TOTAL,
                    Metrics.RESULT, Metrics.RESULT_SUCCESS);
        } else {
            BusinessMetrics.increment(meterRegistry, Metrics.PAY_REFUND_TOTAL,
                    Metrics.RESULT, Metrics.RESULT_PROCESSING);
        }
    }

    private void markRefundStatus(Long refundBillId, RefundStatus refundStatus) {
        RefundBill updateRefundBill = new RefundBill();
        updateRefundBill.setId(refundBillId);
        updateRefundBill.setRefundStatus(refundStatus.getCode());
        refundBillMapper.updateById(updateRefundBill);
    }

    /**
     * 查询退款单状态。订单侧只有拿到 refundStatus=2（渠道确认已退款）才允许把订单置为已退单：
     * 只凭「退款请求返回成功」就置已退单，等于把「受理」当「到账」，用户可能一分钱都没收到。
     *
     * @param refundStatusDto 订单号（分片键）+ 退款单号
     * @return 退款单状态；<b>查不到退款单也返回空状态</b>，由调用方按「未确认」处理
     */
    public RefundStatusVo refundStatus(RefundStatusDto refundStatusDto) {
        RefundStatusVo refundStatusVo = new RefundStatusVo();
        refundStatusVo.setOutOrderNo(refundStatusDto.getOutOrderNo());
        refundStatusVo.setOutRefundNo(refundStatusDto.getOutRefundNo());
        RefundBill refundBill = refundBillMapper.selectOne(Wrappers.lambdaQuery(RefundBill.class)
                .eq(RefundBill::getOutOrderNo, refundStatusDto.getOutOrderNo())
                .eq(RefundBill::getOutRefundNo, refundStatusDto.getOutRefundNo())
                .eq(RefundBill::getStatus, 1));
        if (Objects.isNull(refundBill)) {
            log.error("退款单状态查询：退款单不存在 outOrderNo : {} outRefundNo : {}",
                    refundStatusDto.getOutOrderNo(), refundStatusDto.getOutRefundNo());
            return refundStatusVo;
        }
        refundStatusVo.setRefundStatus(refundBill.getRefundStatus());
        refundStatusVo.setRefundTime(refundBill.getRefundTime());
        return refundStatusVo;
    }

    public PayBillVo detail(PayBillDto payBillDto) {
        PayBillVo payBillVo = new PayBillVo();
        LambdaQueryWrapper<PayBill> payBillLambdaQueryWrapper =
                Wrappers.lambdaQuery(PayBill.class).eq(PayBill::getOutOrderNo, payBillDto.getOrderNumber());
        PayBill payBill = payBillMapper.selectOne(payBillLambdaQueryWrapper);
        if (Objects.nonNull(payBill)) {
            BeanUtil.copyProperties(payBill, payBillVo);
        }
        return payBillVo;
    }
}
