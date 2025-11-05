package com.ticketflow.config;

import com.ticketflow.handler.BloomFilterHandler;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Bloom 过滤器自动配置——注册 BloomFilterHandler Bean。
 *
 * 在容器启动时初始化 Redisson RBloomFilter，若不存在则 tryInit
 */
@EnableConfigurationProperties(BloomFilterProperties.class)
public class BloomFilterAutoConfiguration {
    
    /**
     * 布隆过滤器
     */
    @Bean
    public BloomFilterHandler rBloomFilterUtil(RedissonClient redissonClient, BloomFilterProperties bloomFilterProperties) {
        return new BloomFilterHandler(redissonClient, bloomFilterProperties);
    }
}
