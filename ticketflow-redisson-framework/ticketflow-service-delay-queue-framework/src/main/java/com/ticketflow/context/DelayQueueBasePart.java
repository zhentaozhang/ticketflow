package com.ticketflow.context;

import com.ticketflow.config.DelayQueueProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.redisson.api.RedissonClient;

/**
 * 延迟队列基础配置——封装 RedissonClient + 分区数等全局参数。
 *
 * 由 DelayQueueContext 在初始化时创建，传递给每个 DelayQueuePart
 */
@Data
@AllArgsConstructor
public class DelayQueueBasePart {
    
    private final RedissonClient redissonClient;
    
    private final DelayQueueProperties delayQueueProperties;
}
