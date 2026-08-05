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
import com.ticketflow.dto.TradeCheckDto;
import com.ticketflow.entity.PayBill;
import com.ticketflow.entity.RefundBill;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.PayBillStatus;
import com.ticketflow.enums.PayChannel;
import com.ticketflow.exception.TicketFlowFrameException;
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
import com.ticketflow.vo.TradeCheckVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
     * 通用支付，用订单号加锁防止多次支付成功，不依赖第三方支付的幂等性
     *
     */
    @ServiceLock(name = COMMON_PAY, keys = {"#payDto.orderNumber"})
    @Transactional(rollbackFor = Exception.class)
    public String commonPay(PayDto payDto) {
        // 查询已有账单：非 NO_PAY 状态表示已支付/已取消/已退款，拒绝重复支付
        LambdaQueryWrapper<PayBill> payBillLambdaQueryWrapper =
                Wrappers.lambdaQuery(PayBill.class).eq(PayBill::getOutOrderNo, payDto.getOrderNumber());
        PayBill payBill = payBillMapper.selectOne(payBillLambdaQueryWrapper);
        if (Objects.nonNull(payBill) && !Objects.equals(payBill.getPayBillStatus(), PayBillStatus.NO_PAY.getCode())) {
            throw new TicketFlowFrameException(BaseCode.PAY_BILL_IS_NOT_NO_PAY);
        }
        // 委托支付渠道（当前为支付宝）执行支付，返回支付表单/URL
        PayStrategyHandler payStrategyHandler = payStrategyContext.get(payDto.getChannel());
        PayResult pay = payStrategyHandler.pay(String.valueOf(payDto.getOrderNumber()), payDto.getPrice(),
                payDto.getSubject(), payDto.getNotifyUrl(), payDto.getReturnUrl());
        if (pay.isSuccess()) {
            if (Objects.isNull(payBill)) {
                // 首次支付：插入新账单记录（状态为 NO_PAY，等待异步通知确认）
                payBill = new PayBill();
                payBill.setId(uidGenerator.getUid());
                payBill.setOutOrderNo(String.valueOf(payDto.getOrderNumber()));
                payBill.setPayChannel(payDto.getChannel());
                payBill.setPayScene("生产");
                payBill.setSubject(payDto.getSubject());
                payBill.setPayAmount(payDto.getPrice());
                payBill.setPayBillType(payDto.getPayBillType());
                payBill.setPayBillStatus(PayBillStatus.NO_PAY.getCode());
                payBill.setPayTime(DateUtils.now());
                payBillMapper.insert(payBill);
            } else {
                // 重复调起支付：仅更新支付时间（非幂等重试，而是重新生成支付链接）
                PayBill updatePayBill = new PayBill();
                updatePayBill.setId(payBill.getId());
                updatePayBill.setPayTime(DateUtils.now());
                payBillMapper.updateById(updatePayBill);
            }
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

    @Transactional(rollbackFor = Exception.class)
    @ServiceLock(name = TRADE_CHECK, keys = {"#tradeCheckDto.outTradeNo"})
    public TradeCheckVo tradeCheck(TradeCheckDto tradeCheckDto) {
        TradeCheckVo tradeCheckVo = new TradeCheckVo();
        // 查询支付渠道（支付宝）的交易状态和金额
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
            return tradeCheckVo;
        }
        if (payBill.getPayAmount().compareTo(totalAmount) != 0) {
            log.error("支付渠道 和库中账单支付金额不一致 支付渠道支付金额 : {}, 库中账单支付金额 : {}, tradeCheckDto : {}",
                    totalAmount, payBill.getPayAmount(), JSON.toJSONString(tradeCheckDto));
            return tradeCheckVo;
        }
        // 状态不一致时以支付渠道为准，同步更新本地账单状态
        if (!Objects.equals(payBill.getPayBillStatus(), payBillStatus)) {
            log.warn("支付渠道和库中账单交易状态不一致 支付渠道payBillStatus : {}, 库中payBillStatus : {}, tradeCheckDto : {}",
                    payBillStatus, payBill.getPayBillStatus(), JSON.toJSONString(tradeCheckDto));
            PayBill updatePayBill = new PayBill();
            updatePayBill.setId(payBill.getId());
            updatePayBill.setPayBillStatus(payBillStatus);
            LambdaUpdateWrapper<PayBill> payBillLambdaUpdateWrapper =
                    Wrappers.lambdaUpdate(PayBill.class).eq(PayBill::getOutOrderNo, outTradeNo);
            payBillMapper.update(updatePayBill, payBillLambdaUpdateWrapper);
            return tradeCheckVo;
        }
        return tradeCheckVo;
    }

    @ServiceLock(name = COMMON_PAY, keys = {"#refundDto.orderNumber"})
    @Transactional(rollbackFor = Exception.class)
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
        // 累计已退金额（含渠道受理中未完成的退款单，不含退款失败的终态单），防止超退
        BigDecimal refundedAmount = refundBillMapper.selectList(
                        Wrappers.lambdaQuery(RefundBill.class)
                                .eq(RefundBill::getOutOrderNo, refundDto.getOrderNumber())
                                .eq(RefundBill::getStatus, 1)
                                .in(RefundBill::getRefundStatus, 1, 2))
                .stream().map(RefundBill::getRefundAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (refundedAmount.add(refundDto.getAmount()).compareTo(payBill.getPayAmount()) > 0) {
            throw new TicketFlowFrameException(BaseCode.REFUND_AMOUNT_GREATER_THAN_PAY_AMOUNT);
        }

        // 每笔退款独立生成退款单号，作为渠道侧幂等键与退款状态查询依据
        String outRefundNo = String.valueOf(uidGenerator.getUid());
        // 调用支付渠道退款
        PayStrategyHandler payStrategyHandler = payStrategyContext.get(refundDto.getChannel());
        RefundResult refundResult = payStrategyHandler.refund(refundDto.getOrderNumber(), refundDto.getAmount(),
                payBill.getPayAmount(), refundDto.getReason(), outRefundNo);
        if (!refundResult.isSuccess()) {
            throw new TicketFlowFrameException(BaseCode.REFUND_ERROR.getCode(), refundResult.getMessage());
        }
        // 落退款记录：refundStatus 1=渠道已受理处理中，2=已退款成功
        RefundBill refundBill = new RefundBill();
        refundBill.setId(uidGenerator.getUid());
        refundBill.setOutOrderNo(payBill.getOutOrderNo());
        refundBill.setPayBillId(payBill.getId());
        refundBill.setOutRefundNo(outRefundNo);
        refundBill.setRefundAmount(refundDto.getAmount());
        refundBill.setRefundStatus(refundResult.getRefundStatus());
        refundBill.setRefundTime(DateUtils.now());
        refundBill.setReason(refundDto.getReason());
        refundBillMapper.insert(refundBill);
        // 渠道已确认成功（refundStatus=2）且累计已退达到支付金额才将账单置为 REFUND；
        // 渠道处理中（refundStatus=1，常见于微信）保持 PAY，由 RefundCheckTask 确认成功后翻转；
        // 部分退款保持 PAY 可继续退；条件更新兜底并发：0 行说明账单状态已被其他流程更新，不覆盖
        if (Objects.equals(refundResult.getRefundStatus(), 2)
                && refundedAmount.add(refundDto.getAmount()).compareTo(payBill.getPayAmount()) >= 0) {
            PayBill updatePayBill = new PayBill();
            updatePayBill.setPayBillStatus(PayBillStatus.REFUND.getCode());
            LambdaUpdateWrapper<PayBill> payBillLambdaUpdateWrapper =
                    Wrappers.lambdaUpdate(PayBill.class)
                            .eq(PayBill::getOutOrderNo, payBill.getOutOrderNo())
                            .eq(PayBill::getPayBillStatus, PayBillStatus.PAY.getCode());
            payBillMapper.update(updatePayBill, payBillLambdaUpdateWrapper);
        }
        return outRefundNo;
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
