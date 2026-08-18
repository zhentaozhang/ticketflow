package com.ticketflow.scheduletask;

import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ticketflow.BusinessThreadPool;
import com.ticketflow.client.ProgramClient;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.dto.ReduceRemainNumberDto;
import com.ticketflow.dto.TicketCategoryCountDto;
import com.ticketflow.entity.Order;
import com.ticketflow.entity.OrderTicketUser;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.OrderStatus;
import com.ticketflow.enums.ProgramOrderVersion;
import com.ticketflow.enums.SellStatus;
import com.ticketflow.mapper.OrderMapper;
import com.ticketflow.mapper.OrderTicketUserMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.util.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * V5 订单 DB 库存投影任务。
 * <p>
 * V5 下单链路中 Redis 是唯一库存权威，建单时不再同步 Feign 扣 DB（见 OrderService.createMq V5 分支）。
 * 本任务把已落库的 V5 订单"投影"到 program-service 的 DB：d_seat → LOCK、d_ticket_category.remain_number 扣减，
 * 使 DB 账本与 Redis 收敛，支付/取消路径因此能按 V4 的 LOCK 中间态语义工作。
 * <p>
 * 幂等与容错：
 * <ul>
 *   <li>按 orderNumber 写 Redis 投影完成标记（TTL 1h），重复扫描时跳过；标记丢失时重放由
 *       operateSeatLockAndTicketCategoryRemainNumber 的 @RepeatExecuteLimit 与"座位已非 NO_SOLD 视为已完成"兜底；</li>
 *   <li>投影只针对 NO_PAY 的 V5 订单；已支付/已取消订单跳过；</li>
 *   <li>Feign 失败（program 服务暂不可用）记日志，下轮重试。</li>
 * </ul>
 */
@Slf4j
@Component
public class OrderDbProjectionTask {

    /** 只投影最近 5 分钟内创建的订单（更早的 NO_PAY 订单已被延迟取消/对账兜底，无需投影） */
    private static final int PROJECTION_WINDOW_MINUTES = 5;

    /** 每周期最多投影的订单数（有界扫描，防止大量积压时每 5s 全量扫描 + 逐单 Redis 标记检查压垮 Redis/DB） */
    private static final int PROJECTION_BATCH_LIMIT = 200;

    private static final long PROJECTION_MARKER_TTL_HOURS = 1;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderTicketUserMapper orderTicketUserMapper;

    @Autowired
    private ProgramClient programClient;

    @Autowired
    private RedisCache redisCache;

    @Scheduled(fixedDelay = 10000)
    public void projectionTask() {
        BusinessThreadPool.execute(() -> {
            try {
                List<Order> v5Orders = orderMapper.selectList(Wrappers.lambdaQuery(Order.class)
                        .eq(Order::getOrderVersion, ProgramOrderVersion.V5_VERSION.getValue())
                        .eq(Order::getOrderStatus, OrderStatus.NO_PAY.getCode())
                        .ge(Order::getCreateOrderTime, DateUtils.addMinute(DateUtils.now(), -PROJECTION_WINDOW_MINUTES))
                        .orderByAsc(Order::getCreateOrderTime)
                        .last("LIMIT " + PROJECTION_BATCH_LIMIT));
                if (CollectionUtil.isEmpty(v5Orders)) {
                    return;
                }
                int projectedCount = 0;
                for (Order order : v5Orders) {
                    RedisKeyBuild markerKey = RedisKeyBuild.createRedisKey(RedisKeyManage.V5_ORDER_DB_PROJECTED, order.getOrderNumber());
                    if (redisCache.get(markerKey, String.class) != null) {
                        continue;
                    }
                    if (projectOrder(order)) {
                        projectedCount++;
                        try {
                            redisCache.set(markerKey, String.valueOf(order.getOrderNumber()), PROJECTION_MARKER_TTL_HOURS, TimeUnit.HOURS);
                        } catch (Exception e) {
                            log.error("V5订单DB投影标记写入失败 orderNumber : {}", order.getOrderNumber(), e);
                        }
                    }
                }
                if (projectedCount > 0) {
                    log.info("V5订单DB投影完成 本批数量 : {}", projectedCount);
                }
            } catch (Exception e) {
                log.error("V5订单DB投影任务异常", e);
            }
        });
    }

    /**
     * 投影单个订单：Feign 调 program-service 锁座位 + 扣余票。
     *
     * @return true 表示投影完成（含"已由其他路径投影/终态"的幂等完成）
     */
    private boolean projectOrder(Order order) {
        List<OrderTicketUser> orderTicketUserList = orderTicketUserMapper.selectList(
                Wrappers.lambdaQuery(OrderTicketUser.class).eq(OrderTicketUser::getOrderNumber, order.getOrderNumber()));
        if (CollectionUtil.isEmpty(orderTicketUserList)) {
            log.warn("V5订单投影跳过 无购票人订单 orderNumber : {}", order.getOrderNumber());
            return true;
        }
        Map<Long, Long> countMap = orderTicketUserList.stream()
                .collect(Collectors.groupingBy(OrderTicketUser::getTicketCategoryId, Collectors.counting()));
        ReduceRemainNumberDto reduceRemainNumberDto = new ReduceRemainNumberDto();
        reduceRemainNumberDto.setProgramId(order.getProgramId());
        reduceRemainNumberDto.setSellStatus(SellStatus.LOCK.getCode());
        reduceRemainNumberDto.setSeatIdList(orderTicketUserList.stream().map(OrderTicketUser::getSeatId).collect(Collectors.toList()));
        reduceRemainNumberDto.setTicketCategoryCountDtoList(countMap.entrySet().stream()
                .map(entry -> new TicketCategoryCountDto(entry.getKey(), entry.getValue()))
                .toList());
        ApiResponse<Boolean> programApiResponse = programClient.operateSeatLockAndTicketCategoryRemainNumber(reduceRemainNumberDto);
        if (Objects.equals(programApiResponse.getCode(), BaseCode.SUCCESS.getCode())) {
            return true;
        }
        // 座位已非 NO_SOLD（已被支付置 SOLD / 已被取消置 NO_SOLD / 已被其他投影置 LOCK）视为已投影完成
        if (Objects.equals(programApiResponse.getCode(), BaseCode.SEAT_IS_NOT_NOT_SOLD.getCode())
                || Objects.equals(programApiResponse.getCode(), BaseCode.SEAT_SOLD.getCode())) {
            log.info("V5订单投影跳过 座位已非未售卖 orderNumber : {}", order.getOrderNumber());
            return true;
        }
        log.warn("V5订单DB投影失败 待下轮重试 orderNumber : {} 响应 : {}",
                order.getOrderNumber(), com.alibaba.fastjson.JSON.toJSONString(programApiResponse));
        return false;
    }
}
