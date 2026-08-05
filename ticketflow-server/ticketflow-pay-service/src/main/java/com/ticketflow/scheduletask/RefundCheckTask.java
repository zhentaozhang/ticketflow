package com.ticketflow.scheduletask;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ticketflow.BusinessThreadPool;
import com.ticketflow.entity.PayBill;
import com.ticketflow.entity.RefundBill;
import com.ticketflow.enums.PayBillStatus;
import com.ticketflow.mapper.PayBillMapper;
import com.ticketflow.mapper.RefundBillMapper;
import com.ticketflow.pay.PayStrategyContext;
import com.ticketflow.pay.PayStrategyHandler;
import com.ticketflow.pay.RefundResult;
import com.ticketflow.util.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 退款状态确认定时任务（默认每5分钟）。
 * 微信退款可能异步完成（PROCESSING），本任务轮询处理中的退款单（refundStatus=1），
 * 确认成功后落库并累计判断账单是否可置为已退款（REFUND）；
 * 确认失败置终态（3），避免对同一退款单无限重试
 */
@Slf4j
@Component
public class RefundCheckTask {

    /**
     * 退款处理中
     */
    private static final int REFUND_STATUS_PROCESSING = 1;

    /**
     * 已退款成功
     */
    private static final int REFUND_STATUS_SUCCESS = 2;

    /**
     * 退款失败
     */
    private static final int REFUND_STATUS_FAIL = 3;

    @Autowired
    private RefundBillMapper refundBillMapper;

    @Autowired
    private PayBillMapper payBillMapper;

    @Autowired
    private PayStrategyContext payStrategyContext;

    @Scheduled(cron = "0 0/5 * * * ?")
    public void refundCheckTask() {
        BusinessThreadPool.execute(this::processPendingRefunds);
    }

    /**
     * 处理所有处理中的退款单。独立成 public 方法便于单元测试直接调用。
     * 注意：与 PayService.refund 并发执行时不持有 COMMON_PAY 锁，
     * 翻转账单状态使用条件更新（eq PAY）防覆盖；超退由渠道侧
     * "退款金额不得超过原订单金额"校验兜底。
     */
    public void processPendingRefunds() {
        try {
            List<RefundBill> refundBillList = refundBillMapper.selectList(
                    Wrappers.lambdaQuery(RefundBill.class)
                            .eq(RefundBill::getRefundStatus, REFUND_STATUS_PROCESSING)
                            .eq(RefundBill::getStatus, 1));
            if (refundBillList.isEmpty()) {
                return;
            }
            log.info("退款状态确认任务执行，处理中退款单数量 : {}", refundBillList.size());
            for (RefundBill refundBill : refundBillList) {
                try {
                    checkRefund(refundBill);
                } catch (Exception e) {
                    log.error("退款状态确认异常 refundBillId : {}", refundBill.getId(), e);
                }
            }
        } catch (Exception e) {
            log.error("refund check task error", e);
        }
    }

    private void checkRefund(RefundBill refundBill) {
        PayBill payBill = payBillMapper.selectById(refundBill.getPayBillId());
        if (Objects.isNull(payBill)) {
            log.error("退款确认：支付账单不存在 refundBillId : {}", refundBill.getId());
            return;
        }
        PayStrategyHandler payStrategyHandler = payStrategyContext.get(payBill.getPayChannel());
        RefundResult refundResult =
                payStrategyHandler.queryRefund(payBill.getOutOrderNo(), refundBill.getOutRefundNo());
        if (Objects.equals(refundResult.getRefundStatus(), REFUND_STATUS_SUCCESS)) {
            // 退款成功：更新退款单状态，累计已退达到支付金额则账单置为已退款（条件更新防并发覆盖）
            RefundBill updateRefundBill = new RefundBill();
            updateRefundBill.setId(refundBill.getId());
            updateRefundBill.setRefundStatus(REFUND_STATUS_SUCCESS);
            updateRefundBill.setRefundTime(DateUtils.now());
            refundBillMapper.updateById(updateRefundBill);
            // 累计已退金额只统计处理中/已成功的退款单，排除退款失败终态单
            BigDecimal refundedAmount = refundBillMapper.selectList(
                            Wrappers.lambdaQuery(RefundBill.class)
                                    .eq(RefundBill::getOutOrderNo, payBill.getOutOrderNo())
                                    .eq(RefundBill::getStatus, 1)
                                    .in(RefundBill::getRefundStatus, REFUND_STATUS_PROCESSING, REFUND_STATUS_SUCCESS))
                    .stream().map(RefundBill::getRefundAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (refundedAmount.compareTo(payBill.getPayAmount()) >= 0) {
                PayBill updatePayBill = new PayBill();
                updatePayBill.setPayBillStatus(PayBillStatus.REFUND.getCode());
                payBillMapper.update(updatePayBill, Wrappers.lambdaUpdate(PayBill.class)
                        .eq(PayBill::getOutOrderNo, payBill.getOutOrderNo())
                        .eq(PayBill::getPayBillStatus, PayBillStatus.PAY.getCode()));
            }
            return;
        }
        if (Objects.equals(refundResult.getRefundStatus(), REFUND_STATUS_PROCESSING)) {
            // 仍在处理中，留待下轮轮询
            return;
        }
        // 退款失败/关闭：置终态并告警，避免无限重试
        log.error("退款确认失败 refundBillId : {} outRefundNo : {} message : {}",
                refundBill.getId(), refundBill.getOutRefundNo(), refundResult.getMessage());
        RefundBill updateRefundBill = new RefundBill();
        updateRefundBill.setId(refundBill.getId());
        updateRefundBill.setRefundStatus(REFUND_STATUS_FAIL);
        refundBillMapper.updateById(updateRefundBill);
    }
}
