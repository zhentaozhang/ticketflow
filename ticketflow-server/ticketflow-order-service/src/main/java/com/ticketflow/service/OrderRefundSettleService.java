package com.ticketflow.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ticketflow.entity.Order;
import com.ticketflow.entity.OrderTicketUser;
import com.ticketflow.enums.OrderStatus;
import com.ticketflow.enums.ReconciliationStatus;
import com.ticketflow.mapper.OrderMapper;
import com.ticketflow.mapper.OrderTicketUserMapper;
import com.ticketflow.observability.BusinessMetrics;
import com.ticketflow.observability.Metrics;
import com.ticketflow.util.DateUtils;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Objects;

/**
 * 「渠道已收款、本地订单已取消」的退款在本地侧的收尾。
 * <p>
 * 为什么单独成 bean：{@code refundClosedOrder}/{@code payCheck} 是同一个类里的私有/公有方法，
 * 直接给它们贴 {@code @Transactional} 不会被代理拦截（自调用），事务根本不生效。
 * <p>
 * 为什么收尾要事务 + CAS：退款前订单是「已取消」，收尾必须把它推进到「已退单」，
 * 并同时把购票人子表、退款时间、对账标记一起写掉——否则
 * d_order 是已退单而 d_order_ticket_user 还是已取消，限购额度（accountOrderCount
 * 只认 order_status in (1,3)）永远不释放，资金侧也拿不到订单维度的退款时间。
 * CAS 条件为 order_status=CANCEL：重复回调不会把已退单/已支付的行覆盖掉。
 * <p>
 * 资金顺序不可颠倒：渠道退款先发生（幂等键保证重试不二次退款），本地收尾后发生；
 * 收尾失败返回 false，让回调应答失败、对账任务下一轮重试，而不是当作成功吞掉。
 */
@Slf4j
@Service
public class OrderRefundSettleService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderTicketUserMapper orderTicketUserMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    /**
     * 退款成功后的本地收尾：主表 CAS 置已退单 + 写退款时间/对账标记，购票人子表同步置已退单。
     *
     * @param orderNumber 订单号
     * @return true = 本地已是已退单（本次写入成功或此前已写入）；false = 本地状态没推进，调用方必须让渠道/对账重试
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean settleRefund(Long orderNumber) {
        Date now = DateUtils.now();
        Order updateOrder = new Order();
        updateOrder.setOrderStatus(OrderStatus.REFUND.getCode());
        updateOrder.setRefundOrderTime(now);
        // 退款是「对账发现问题并已处理完毕」的终态：写掉它，订单才不会一直挂在待对账里被反复扫
        updateOrder.setPayReconciliationStatus(ReconciliationStatus.RECONCILIATION_FINISH.getCode());
        updateOrder.setEditTime(now);
        int rows = orderMapper.update(updateOrder, Wrappers.lambdaUpdate(Order.class)
                .eq(Order::getOrderNumber, orderNumber)
                .eq(Order::getOrderStatus, OrderStatus.CANCEL.getCode()));
        if (rows <= 0) {
            return verifyAlreadyRefunded(orderNumber);
        }
        // 购票人子表：与主表同库同分片（bindingTables），一并置已退单，释放限购额度
        OrderTicketUser updateTicketUser = new OrderTicketUser();
        updateTicketUser.setOrderStatus(OrderStatus.REFUND.getCode());
        updateTicketUser.setEditTime(now);
        int ticketUserRows = orderTicketUserMapper.update(updateTicketUser,
                Wrappers.lambdaUpdate(OrderTicketUser.class)
                        .eq(OrderTicketUser::getOrderNumber, orderNumber)
                        .eq(OrderTicketUser::getOrderStatus, OrderStatus.CANCEL.getCode()));
        if (ticketUserRows <= 0) {
            // 主表已经推进了，子表却一条都没动：不抛异常（钱已经退了，不能让事务回滚掉主表状态），
            // 但限购额度可能仍被占用，留一条告警日志给人核对
            log.warn("退款收尾：购票人子表没有「已取消」记录可更新，限购额度可能仍被占用 orderNumber : {}",
                    orderNumber);
        }
        BusinessMetrics.increment(meterRegistry, Metrics.ORDER_REFUND_SETTLE_TOTAL,
                Metrics.RESULT, Metrics.RESULT_SUCCESS);
        log.info("退款收尾完成：订单置已退单并写退款时间 orderNumber : {} 子表更新行数 : {}",
                orderNumber, ticketUserRows);
        return true;
    }

    private boolean verifyAlreadyRefunded(Long orderNumber) {
        Order latest;
        try {
            latest = orderMapper.selectOne(Wrappers.lambdaQuery(Order.class)
                    .eq(Order::getOrderNumber, orderNumber));
        } catch (Exception e) {
            log.error("退款收尾：主表 CAS 未命中后复查失败 orderNumber : {}", orderNumber, e);
            BusinessMetrics.increment(meterRegistry, Metrics.ORDER_REFUND_SETTLE_TOTAL,
                    Metrics.RESULT, Metrics.RESULT_ERROR);
            return false;
        }
        if (Objects.nonNull(latest) && Objects.equals(latest.getOrderStatus(), OrderStatus.REFUND.getCode())) {
            log.info("退款收尾：重复回调/重试命中幂等，订单已是已退单 orderNumber : {}", orderNumber);
            BusinessMetrics.increment(meterRegistry, Metrics.ORDER_REFUND_SETTLE_TOTAL,
                    Metrics.RESULT, Metrics.RESULT_IDEMPOTENT);
            return true;
        }
        log.error("退款已在渠道侧成功，但本地订单不是「已取消」也无法推进到「已退单」，需要人工核对 orderNumber : {} 当前状态 : {}",
                orderNumber, Objects.isNull(latest) ? "订单不存在" : latest.getOrderStatus());
        BusinessMetrics.increment(meterRegistry, Metrics.ORDER_REFUND_SETTLE_TOTAL,
                Metrics.RESULT, Metrics.RESULT_ERROR);
        return false;
    }
}
