package com.ticketflow.service.lua;

import com.alibaba.fastjson.JSON;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.service.ApiRestrictData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * Lua 脚本加载与执行桥接层。
 * 在 @PostConstruct 阶段将 apiLimit.lua 加载为 DefaultRedisScript，
 * 运行时通过 redisCache.getInstance().execute() 在 Redis 服务端原子执行。
 *
 * KEYS 传参方式：将 ApiRestrictService 构造的 JSON 参数打包为 List<String> 传入 Lua。
 * 返回值：ApiRestrictData JSON（triggerResult / triggerCallStat / apiCount / threshold / messageIndex）
 */
@Slf4j
@Component
public class ApiRestrictCacheOperate {
    
    @Autowired
    private RedisCache redisCache;
    
    private DefaultRedisScript<String> redisScript;
    
    @PostConstruct
    public void init(){
        try {
            redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/apiLimit.lua")));
            redisScript.setResultType(String.class);
        } catch (Exception e) {
            log.error("redisScript init lua error",e);
        }
    }
    
    public ApiRestrictData apiRuleOperate(List<String> keys, Object[] args){
        Object object = redisCache.getInstance().execute(redisScript, keys, args);
        return JSON.parseObject((String)object, ApiRestrictData.class);
    }
}
