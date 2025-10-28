package com.ticketflow.redis.config;

import com.ticketflow.redis.RedisCacheImpl;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis缓存自动配置。将RedisCacheImpl注册为Spring Bean，提供统一缓存操作入口。
 **/
public class RedisCacheAutoConfig {
    
    @Bean
    public RedisCacheImpl redisCache(@Qualifier("redisToolStringRedisTemplate") StringRedisTemplate stringRedisTemplate){
        return new RedisCacheImpl(stringRedisTemplate);
    }
}
