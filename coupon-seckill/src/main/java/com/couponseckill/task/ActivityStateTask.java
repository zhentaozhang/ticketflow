package com.couponseckill.task;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.couponseckill.config.ShardingContext;
import com.couponseckill.entity.FlashSaleActivity;
import com.couponseckill.mapper.FlashSaleActivityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 活动状态推进：未开始 → 进行中 → 已结束（对应 docs/01-技术设计.md §2.3 状态机）。
 * 抢购权威校验在 Lua（时间窗），此任务只负责 DB 状态同步与管理端展示。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityStateTask {

    private final FlashSaleActivityMapper activityMapper;

    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    public void advance() {
        LocalDateTime now = LocalDateTime.now();

        // 未开始 → 进行中
        int started = activityMapper.update(null, new LambdaUpdateWrapper<FlashSaleActivity>()
                .set(FlashSaleActivity::getStatus, FlashSaleActivity.STATUS_ONGOING)
                .eq(FlashSaleActivity::getStatus, FlashSaleActivity.STATUS_NOT_STARTED)
                .le(FlashSaleActivity::getStartTime, now));

        // 进行中 → 已结束
        int ended = activityMapper.update(null, new LambdaUpdateWrapper<FlashSaleActivity>()
                .set(FlashSaleActivity::getStatus, FlashSaleActivity.STATUS_ENDED)
                .eq(FlashSaleActivity::getStatus, FlashSaleActivity.STATUS_ONGOING)
                .le(FlashSaleActivity::getEndTime, now));

        if (started > 0 || ended > 0) {
            log.info("[state-advance] started={} ended={} at {}", started, ended, now);
        }
    }
}
