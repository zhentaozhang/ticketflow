package com.ticketflow.scheduletask;

import com.ticketflow.shardingsphere.ShardingMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时输出基因法分片路由分布快照，用于观察各分库命中是否有热点。
 * 仅记录到日志，不依赖外部监控组件。
 */
@Slf4j
@Component
public class ShardingRoutingMonitorTask {

    @Scheduled(fixedDelay = 60000)
    public void report() {
        log.info(ShardingMetrics.snapshot());
    }
}