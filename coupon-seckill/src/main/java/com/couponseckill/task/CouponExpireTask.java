package com.couponseckill.task;

import com.couponseckill.config.ShardingContext;
import com.couponseckill.mapper.UserCouponMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 券过期任务：未使用且超过有效期的券批量置过期（跨分片）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponExpireTask {

    private final UserCouponMapper couponMapper;

    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    public void expire() {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        int total = 0;
        for (int s = 0; s < ShardingContext.SHARD_COUNT; s++) {
            String table = ShardingContext.shardTable("user_coupon", (long) s);
            int rows = couponMapper.expireInShard(table, now);
            total += rows;
        }
        if (total > 0) {
            log.info("[coupon-expire] expired {} coupons at {}", total, now);
        }
    }
}
