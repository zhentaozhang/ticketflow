package com.ticketflow.service.lua;

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
 * Lua 脚本桥接：节目数据编辑后的原子 Redis 更新。
 * 加载 programDataResolution.lua，在 Redis 中执行 HINCRBY 恢复余票 +
 * HDEL 删除旧座位 + HMSET 写入新座位的原子操作。
 *
 * 被 ProgramManageService 在后台修改节目数据时调用
 */
@Slf4j
@Component
public class ProgramCacheResolutionOperate {
    
    @Autowired
    private RedisCache redisCache;
    
    private DefaultRedisScript redisScript;
    
    /**
     * 加载 Lua 脚本 programDataResolution.lua。
     * 该脚本原子执行：HINCRBY 恢复/扣减余票 → HDEL 删除旧座位 → HMSET 写入新座位。
     */
    @PostConstruct
    public void init(){
        try {
            redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/programDataResolution.lua")));
            redisScript.setResultType(Integer.class);
        } catch (Exception e) {
            log.error("redisScript init lua error",e);
        }
    }
    
    /**
     * 执行节目数据编辑的 Lua 脚本（后台修改节目数据时调用）。
     *
     * @param keys Redis key 列表（节目座位/余票 Hash key）
     * @param args Lua 脚本参数（新座位数据、余票变化量等）
     */
    public void programCacheOperate(List<String> keys, String[] args){
        redisCache.getInstance().execute(redisScript, keys, args);
    }
}
