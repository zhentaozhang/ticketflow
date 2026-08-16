package com.ticketflow.scheduletask;

import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ticketflow.BusinessThreadPool;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.entity.Order;
import com.ticketflow.entity.OrderTicketUser;
import com.ticketflow.enums.OrderStatus;
import com.ticketflow.enums.ProgramOrderVersion;
import com.ticketflow.mapper.OrderMapper;
import com.ticketflow.mapper.OrderTicketUserMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.util.DateUtils;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * V5 库存守恒校验（数值级对账）。
 * <p>
 * V5 中 Redis 是唯一库存权威、DB 座位/余票是派生投影。本任务按节目校验 Redis 座位三区
 * （lock / sold hash 的座位数）与 DB 订单（d_order + d_order_ticket_user 的座位状态分布）
 * 是否收敛一致，发现"Redis 已扣但 DB 未投影 / DB 有单但 Redis 未锁"等漂移时告警 + Prometheus 计数。
 * <p>
 * 注意：Redis lock 区包含"未支付但已锁"的座位，DB 侧对应 order_status=NO_PAY 的订单座位；
 * Redis sold 区对应 order_status=PAY 的订单座位。CANCEL/REFUND 订单的座位两侧均已释放，不计入。
 */
@Slf4j
@Component
public class V5StockConservationTask {

    private static final int WINDOW_MINUTES = 10;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderTicketUserMapper orderTicketUserMapper;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private MeterRegistry meterRegistry;

    @Scheduled(fixedDelay = 60000)
    public void checkStockConservation() {
        BusinessThreadPool.execute(() -> {
            try {
                List<Order> recentV5Orders = orderMapper.selectList(Wrappers.lambdaQuery(Order.class)
                        .eq(Order::getOrderVersion, ProgramOrderVersion.V5_VERSION.getValue())
                        .ge(Order::getCreateOrderTime, DateUtils.addMinute(DateUtils.now(), -WINDOW_MINUTES)));
                if (CollectionUtil.isEmpty(recentV5Orders)) {
                    return;
                }
                Set<Long> programIdSet = recentV5Orders.stream().map(Order::getProgramId).collect(Collectors.toSet());
                for (Long programId : programIdSet) {
                    List<Order> programRecentOrders = recentV5Orders.stream()
                            .filter(order -> Objects.equals(order.getProgramId(), programId))
                            .toList();
                    checkProgram(programRecentOrders);
                }
            } catch (Exception e) {
                log.error("V5库存守恒校验异常", e);
            }
        });
    }

    /**
     * 只校验近 WINDOW_MINUTES 内的订单（避免历史遗留订单导致每次 60s 扫描全表）。
     * OrderTicketUser 按 orderNumbers 一次批量查询，避免逐单 N 次 DB 往返。
     */
    private void checkProgram(List<Order> v5Orders) {
        if (CollectionUtil.isEmpty(v5Orders)) {
            return;
        }
        Long programId = v5Orders.get(0).getProgramId();
        //DB 侧：NO_PAY 订单座位 → 锁定；PAY 订单座位 → 已售
        Map<Long, Integer> dbLocked = new HashMap<>();
        Map<Long, Integer> dbSold = new HashMap<>();
        Set<Long> categoryIdSet = new HashSet<>();
        //按订单号分组：状态 NO_PAY → 锁定、PAY → 已售，其余状态跳过
        Map<Long, Order> activeOrderMap = v5Orders.stream()
                .filter(order -> Objects.equals(order.getOrderStatus(), OrderStatus.NO_PAY.getCode())
                        || Objects.equals(order.getOrderStatus(), OrderStatus.PAY.getCode()))
                .collect(Collectors.toMap(Order::getOrderNumber, order -> order, (v1, v2) -> v2));
        if (activeOrderMap.isEmpty()) {
            return;
        }
        //一次批量查询所有活跃订单的购票人座位
        List<OrderTicketUser> orderTicketUserList = orderTicketUserMapper.selectList(
                Wrappers.lambdaQuery(OrderTicketUser.class).in(OrderTicketUser::getOrderNumber, activeOrderMap.keySet()));
        for (OrderTicketUser orderTicketUser : orderTicketUserList) {
            Order order = activeOrderMap.get(orderTicketUser.getOrderNumber());
            if (order == null) {
                continue;
            }
            Map<Long, Integer> target = Objects.equals(order.getOrderStatus(), OrderStatus.PAY.getCode()) ? dbSold : dbLocked;
            categoryIdSet.add(orderTicketUser.getTicketCategoryId());
            target.merge(orderTicketUser.getTicketCategoryId(), 1, Integer::sum);
        }
        for (Long ticketCategoryId : categoryIdSet) {
            Map<String, String> lockMap = redisCache.getAllMapForHash(
                    RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH, programId, ticketCategoryId),
                    String.class);
            Map<String, String> soldMap = redisCache.getAllMapForHash(
                    RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_SOLD_RESOLUTION_HASH, programId, ticketCategoryId),
                    String.class);
            int redisLocked = lockMap.size();
            int redisSold = soldMap.size();
            int dbLockedCount = dbLocked.getOrDefault(ticketCategoryId, 0);
            int dbSoldCount = dbSold.getOrDefault(ticketCategoryId, 0);
            if (redisLocked != dbLockedCount || redisSold != dbSoldCount) {
                log.warn("V5库存守恒异常 programId : {} ticketCategoryId : {} " +
                                "Redis(locked={}, sold={}) DB(locked={}, sold={})",
                        programId, ticketCategoryId, redisLocked, redisSold, dbLockedCount, dbSoldCount);
                meterRegistry.counter("ticketflow_v5_stock_consistency_violation",
                        "programId", String.valueOf(programId)).increment();
            }
        }
    }
}
