package com.couponseckill.task;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.couponseckill.config.RedisKeys;
import com.couponseckill.config.ShardingContext;
import com.couponseckill.entity.FlashSaleOrder;
import com.couponseckill.mapper.FlashSaleOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 发券超时扫描（docs/01-技术设计.md §9.4）：
 * 处理中(status=0)超过阈值的流水 → 乐观锁标记失败 → 回补 Redis 库存与限购计数。
 * （生产模式可扩展为重新投递 Kafka；独立阶段以回补收敛，保证不悬挂库存。）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IssueTimeoutTask {

    private final FlashSaleOrderMapper orderMapper;
    private final StringRedisTemplate redisTemplate;

    // @Qualifier 不随 Lombok 构造器复制，脚本用字段注入按名称解析
    @Autowired
    @Qualifier("flashRollbackScript")
    private DefaultRedisScript<Long> rollbackScript;

    @org.springframework.beans.factory.annotation.Value("${coupon-seckill.issue.timeout-ms:300000}")
    private long timeoutMs;

    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void scanTimeout() {
        String deadline = LocalDateTime.now().minusNanos(timeoutMs * 1_000_000)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        for (int s = 0; s < ShardingContext.SHARD_COUNT; s++) {
            String table = ShardingContext.shardTable("flash_sale_order", (long) s);
            List<FlashSaleOrder> timeouts = orderMapper.listTimeoutInShard(table, deadline, 200);
            for (FlashSaleOrder order : timeouts) {
                handleTimeout(order);
            }
        }
    }

    private void handleTimeout(FlashSaleOrder order) {
        // 乐观锁：仅 处理中 → 发券失败（避免与正在消费的并发冲突）
        int rows = orderMapper.update(null, new LambdaUpdateWrapper<FlashSaleOrder>()
                .set(FlashSaleOrder::getStatus, FlashSaleOrder.STATUS_ISSUE_FAILED)
                .set(FlashSaleOrder::getUpdateTime, LocalDateTime.now())
                .eq(FlashSaleOrder::getId, order.getId())
                .eq(FlashSaleOrder::getStatus, FlashSaleOrder.STATUS_PROCESSING));
        if (rows == 0) {
            return; // 已被消费处理或已被其他任务接管
        }
        // 回补 Redis 库存 + 限购计数 + 幂等标记（rollback.lua）
        try {
            redisTemplate.execute(rollbackScript, List.of(
                    RedisKeys.stock(order.getActivityId()),
                    RedisKeys.limit(order.getActivityId(), order.getUserId()),
                    RedisKeys.dedup(order.getActivityId(), order.getUserId(), order.getRequestId())));
            log.warn("[issue-timeout-rollback] orderNo={} activityId={} userId={}",
                    order.getOrderNo(), order.getActivityId(), order.getUserId());
        } catch (Exception e) {
            log.error("[issue-timeout-rollback-fail] orderNo={}", order.getOrderNo(), e);
        }
    }
}
