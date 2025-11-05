package com.ticketflow.handle;

import lombok.AllArgsConstructor;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Redisson 客户端封装——提供基本的 KV 读写操作。
 *
 * 内部持有 RedissonClient 实例，暴露 Bucket（set/get/cas）等方法
 */
@AllArgsConstructor
public class RedissonDataHandle {
    
    private final RedissonClient redissonClient;
    
    public String get(String key){
        return (String)redissonClient.getBucket(key).get();
    }
    
    public void set(String key,String value){
        redissonClient.getBucket(key).set(value);
    }
    
    public void set(String key,String value,long timeToLive, TimeUnit timeUnit){
        redissonClient.getBucket(key).set(value,getDuration(timeToLive,timeUnit));
    }
    
    public Duration getDuration(long timeToLive, TimeUnit timeUnit){
        switch (timeUnit) {
            
            case MINUTES -> {
                return Duration.ofMinutes(timeToLive);
            }
            
            case HOURS -> {
                return Duration.ofHours(timeToLive);
            }
            
            case DAYS -> {
                return Duration.ofDays(timeToLive);
            }
            
            default -> {
                return Duration.ofSeconds(timeToLive);
            }
        }
    }
}
