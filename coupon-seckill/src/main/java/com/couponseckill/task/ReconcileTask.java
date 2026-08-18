package com.couponseckill.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.couponseckill.config.ShardingContext;
import com.couponseckill.entity.FlashSaleActivity;
import com.couponseckill.mapper.FlashSaleActivityMapper;
import com.couponseckill.mapper.FlashSaleOrderMapper;
import com.couponseckill.service.ActivityCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 库存对账任务（docs/01-技术设计.md §9.4）：
 * 对每个进行中的活动校验  Redis剩余库存 + DB已发券数 == 总库存；
 * 不一致 → 以 DB 为权威重载 Redis 库存 + 告警日志。
 * 另做限购对账：发放数超过限购的用户 → 告警（修复由运营确认，提供作废入口）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconcileTask {

    private final FlashSaleActivityMapper activityMapper;
    private final FlashSaleOrderMapper orderMapper;
    private final ActivityCacheService cacheService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 15_000)
    public void reconcileStock() {
        List<FlashSaleActivity> activities = activityMapper.selectList(new LambdaQueryWrapper<FlashSaleActivity>()
                .eq(FlashSaleActivity::getStatus, FlashSaleActivity.STATUS_ONGOING)
                .or()
                .eq(FlashSaleActivity::getStatus, FlashSaleActivity.STATUS_NOT_STARTED));

        for (FlashSaleActivity activity : activities) {
            long issued = countIssuedAcrossShards(activity.getId());
            int total = activity.getTotalStock();
            int expectedRedis = total - (int) issued;

            Long redisStock = cacheService.getStock(activity.getId());
            if (redisStock == null) {
                log.warn("[reconcile-stock-missing] activityId={} redis stock key missing, rebuild to {}",
                        activity.getId(), expectedRedis);
                cacheService.setStock(activity.getId(), expectedRedis);
                continue;
            }
            if (redisStock != expectedRedis) {
                log.warn("[reconcile-diff] activityId={} redisStock={} expected={} issued={} total={}, rebuild redis",
                        activity.getId(), redisStock, expectedRedis, issued, total);
                cacheService.setStock(activity.getId(), expectedRedis);
            }
        }
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 30_000)
    public void reconcileLimit() {
        List<FlashSaleActivity> activities = activityMapper.selectList(new LambdaQueryWrapper<FlashSaleActivity>()
                .eq(FlashSaleActivity::getStatus, FlashSaleActivity.STATUS_ONGOING)
                .or()
                .eq(FlashSaleActivity::getStatus, FlashSaleActivity.STATUS_NOT_STARTED));
        for (FlashSaleActivity activity : activities) {
            for (int s = 0; s < ShardingContext.SHARD_COUNT; s++) {
                String table = ShardingContext.shardTable("flash_sale_order", (long) s);
                List<Map<String, Object>> over = orderMapper.findOverLimitInShard(
                        table, activity.getId(), activity.getPerUserLimit());
                if (!over.isEmpty()) {
                    log.warn("[reconcile-over-limit] activityId={} perUserLimit={} overUsers={}",
                            activity.getId(), activity.getPerUserLimit(), over);
                }
            }
        }
    }

    private long countIssuedAcrossShards(Long activityId) {
        long total = 0;
        for (int s = 0; s < ShardingContext.SHARD_COUNT; s++) {
            String table = ShardingContext.shardTable("flash_sale_order", (long) s);
            total += orderMapper.countIssuedInShard(table, activityId);
        }
        return total;
    }
}
