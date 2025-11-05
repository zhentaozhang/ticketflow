package com.ticketflow.config;


import com.ticketflow.context.DelayQueueBasePart;
import com.ticketflow.context.DelayQueueContext;
import com.ticketflow.event.DelayQueueInitHandler;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 延迟队列自动配置——注册 DelayQueueBasePart / DelayQueueContext / DelayQueueInitHandler。
 *
 * 将 RedissonClient + DelayQueueProperties 组合为 DelayQueueBasePart，
 * 供 DelayQueueContext 在发送消息时使用
 */
@EnableConfigurationProperties(DelayQueueProperties.class)
public class DelayQueueAutoConfig {
    
    @Bean
    public DelayQueueInitHandler delayQueueInitHandler(DelayQueueBasePart delayQueueBasePart){
        return new DelayQueueInitHandler(delayQueueBasePart);
    }
   
    @Bean
    public DelayQueueBasePart delayQueueBasePart(RedissonClient redissonClient,DelayQueueProperties delayQueueProperties){
        return new DelayQueueBasePart(redissonClient,delayQueueProperties);
    }
  
    @Bean
    public DelayQueueContext delayQueueContext(DelayQueueBasePart delayQueueBasePart){
        return new DelayQueueContext(delayQueueBasePart);
    }
}
