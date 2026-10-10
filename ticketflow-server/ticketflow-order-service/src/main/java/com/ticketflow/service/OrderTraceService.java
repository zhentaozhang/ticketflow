package com.ticketflow.service;

import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.domain.DiscardOrder;
import com.ticketflow.domain.OrderTraceResult;
import com.ticketflow.domain.PendingOrder;
import com.ticketflow.entity.Order;
import com.ticketflow.enums.OrderStatus;
import com.ticketflow.enums.ReconciliationStatus;
import com.ticketflow.enums.RecordType;
import com.ticketflow.mapper.OrderMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

import static com.ticketflow.constant.Constant.GLIDE_LINE;

/**
 * 单笔订单的排查视图。
 * <p>
 * 从 {@link OrderService} 里拆出来，原因就是那个类太大（1500+ 行，支付/回调/取消/查询/缓存什么都在里面）。
 * 这块职责本身很集中：把链路上几处记录拼起来 + 判断"这一单停在哪一步"，
 * 既不写业务状态，也不参与交易，是纯粹的"读+判断"，适合独立成类。
 */
@Slf4j
@Service
public class OrderTraceService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private RedisCache redisCache;

    /**
     * 单笔订单排查视图：把链路上几处记录一次性拼出来，并给一句“停在哪一步”的结论。
     * <p>
     * 对应 09 里那套排查方法的五处记录：
     * MySQL 订单（事实）→ Redis 扣减流水（依据）→ 建单完成标记（前端终态）
     * → 丢弃队列（DISCARD_ORDER）→ 待裁决队列（PENDING）。
     *
     * @param orderNumber 订单号
     * @return 排查视图
     */
    public OrderTraceResult getOrderTrace(Long orderNumber) {
        OrderTraceResult result = new OrderTraceResult();
        result.setOrderNumber(orderNumber);
        // 前端轮询的终态标记是 Redis 数据，无论订单在不在都值得看一眼
        result.setCreateMarkPresent(redisCache.hasKey(RedisKeyBuild.createRedisKey(
                RedisKeyManage.ORDER_MQ, orderNumber)));

        Order order = orderMapper.selectOne(Wrappers.lambdaQuery(Order.class)
                .eq(Order::getOrderNumber, orderNumber));
        result.setOrderExists(Objects.nonNull(order));
        if (Objects.isNull(order)) {
            result.setHint(buildTraceHint(result));
            return result;
        }

        result.setOrderStatus(order.getOrderStatus());
        result.setPayReconciliationStatus(order.getPayReconciliationStatus());
        result.setProgramId(order.getProgramId());
        result.setUserId(order.getUserId());
        result.setIdentifierId(order.getIdentifierId());
        result.setCreateOrderTime(order.getCreateOrderTime());
        result.setCancelOrderTime(order.getCancelOrderTime());
        result.setPayOrderTime(order.getPayOrderTime());

        // 扣减流水：program-service 扣减时写的，field 格式是“类型_标识id_用户id”，两边共用同一个 Redis
        if (Objects.nonNull(order.getIdentifierId()) && Objects.nonNull(order.getUserId())) {
            String recordField = RecordType.REDUCE.getValue() + GLIDE_LINE
                    + order.getIdentifierId() + GLIDE_LINE + order.getUserId();
            result.setDeductRecordField(recordField);
            result.setDeductRecordPresent(Objects.nonNull(redisCache.getForHash(
                    RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_RECORD, order.getProgramId()),
                    recordField, String.class)));
        }

        // 两个异常队列：只扫前若干条（它们正常会被对账任务消费掉，不应该长期很大）
        result.setDiscarded(existsInDiscardQueue(order));
        result.setPending(existsInPendingQueue(order));
        result.setHint(buildTraceHint(result));
        return result;
    }

    private static final int TRACE_QUEUE_SCAN_LIMIT = 1000;

    private boolean existsInDiscardQueue(Order order) {
        try {
            List<DiscardOrder> discardOrderList = redisCache.rangeForList(RedisKeyBuild.createRedisKey(
                    RedisKeyManage.DISCARD_ORDER, order.getProgramId()), 0, TRACE_QUEUE_SCAN_LIMIT - 1,
                    DiscardOrder.class);
            if (CollectionUtil.isEmpty(discardOrderList)) {
                return false;
            }
            return discardOrderList.stream()
                    .filter(item -> Objects.nonNull(item) && Objects.nonNull(item.getOrderCreateMq()))
                    .anyMatch(item -> Objects.equals(item.getOrderCreateMq().getOrderNumber(), order.getOrderNumber()));
        } catch (Exception e) {
            log.error("排查视图：读丢弃队列失败 orderNumber : {}", order.getOrderNumber(), e);
            return false;
        }
    }

    private boolean existsInPendingQueue(Order order) {
        try {
            List<PendingOrder> pendingOrderList = redisCache.rangeForList(RedisKeyBuild.createRedisKey(
                    RedisKeyManage.ORDER_CREATE_PENDING, order.getProgramId()), 0, TRACE_QUEUE_SCAN_LIMIT - 1,
                    PendingOrder.class);
            if (CollectionUtil.isEmpty(pendingOrderList)) {
                return false;
            }
            return pendingOrderList.stream()
                    .filter(item -> Objects.nonNull(item) && Objects.nonNull(item.getOrderCreateMq()))
                    .anyMatch(item -> Objects.equals(item.getOrderCreateMq().getOrderNumber(), order.getOrderNumber()));
        } catch (Exception e) {
            log.error("排查视图：读待裁决队列失败 orderNumber : {}", order.getOrderNumber(), e);
            return false;
        }
    }

    /**
     * 把“五处记录拼起来”的结果转成一句结论。这里就是排查时人脑里做的那一步判断。
     */
    private String buildTraceHint(OrderTraceResult result) {
        if (!Boolean.TRUE.equals(result.getOrderExists())) {
            if (Boolean.TRUE.equals(result.getDiscarded())) {
                return "扣了库存但建单被丢弃：用户拿到的订单号无效，资源会由对账回滚（坐位会放回池子）";
            }
            if (Boolean.TRUE.equals(result.getPending())) {
                return "扣了库存但发送结果未知（PENDING）：等对账裁决——已建单则保留，未建单则回滚";
            }
            if (Boolean.TRUE.equals(result.getDeductRecordPresent())) {
                return "有扣减流水但没有订单：停在“扣了没建”，等对账回滚资源";
            }
            return "没有受理痕迹：这个订单号不是本系统产生的，或数据已被清理";
        }
        Integer orderStatus = result.getOrderStatus();
        if (Objects.equals(orderStatus, OrderStatus.NO_PAY.getCode())) {
            return Boolean.TRUE.equals(result.getCreateMarkPresent())
                    ? "订单已创建、等待支付（超时会取消并回补）"
                    : "订单已创建但没有完成标记：前端可能轮询不到终态（标记是 Redis 数据）";
        }
        if (Objects.equals(orderStatus, OrderStatus.PAY.getCode())) {
            return "已支付";
        }
        if (Objects.equals(orderStatus, OrderStatus.CANCEL.getCode())) {
            return Objects.equals(result.getPayReconciliationStatus(), ReconciliationStatus.RECONCILIATION_NO.getCode())
                    ? "已取消，但还没做过支付对账：不能排除“用户实际付了钱”（对账任务会去问渠道并退款）"
                    : "已取消，且已完成支付对账（渠道未收款或已退款）";
        }
        if (Objects.equals(orderStatus, OrderStatus.REFUND.getCode())) {
            return "已退单（钱已退回）";
        }
        return "订单状态未知：" + orderStatus;
    }

}
