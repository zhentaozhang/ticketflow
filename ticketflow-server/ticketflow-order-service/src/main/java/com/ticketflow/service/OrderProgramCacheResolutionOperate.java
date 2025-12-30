package com.ticketflow.service;

import com.ticketflow.redis.RedisCache;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Lua 脚本桥接：订单操作后的节目数据原子更新。
 * 加载 OrderProgramDataResolution.lua，在 Redis 中执行：
 *   取消订单：HDEL lock hash → HMSET no_sold hash → HINCRBY 恢复余票
 *   支付订单：HDEL lock hash → HMSET sold hash（余票不变）
 *
 * 被 OrderService.updateProgramRelatedDataResolution() 和
 * OrderService.alipayNotify() 调用
 */
@Slf4j
@Component
public class OrderProgramCacheResolutionOperate {
    
    @Autowired
    private RedisCache redisCache;
    
    private DefaultRedisScript redisScript;
    
    @PostConstruct
    public void init(){
        try {
            redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/OrderProgramDataResolution.lua")));
            redisScript.setResultType(Integer.class);
        } catch (Exception e) {
            log.error("redisScript init lua error",e);
        }
    }
    
    public void programCacheReverseOperate(List<String> keys, Object... args){
        redisCache.getInstance().execute(redisScript, keys, args);
    }
}
