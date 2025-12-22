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
 * Lua 脚本桥接：节目全量删除。
 * 加载 programDel.lua，在 Redis 中原子清理 12 类节目相关 key（含模糊匹配的座位/余票 key）。
 *
 * 被 ProgramManageService 的节目删除功能调用
 */
@Slf4j
@Component
public class ProgramDelCacheData {
    
    @Autowired
    private RedisCache redisCache;
    
    private DefaultRedisScript redisScript;
    
    /**
     * 加载 Lua 脚本 programDel.lua。
     * 该脚本在 Redis 中原子清理 12 类节目相关 key（含模糊匹配的座位/余票 key）。
     */
    @PostConstruct
    public void init(){
        try {
            redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/programDel.lua")));
            redisScript.setResultType(Integer.class);
        } catch (Exception e) {
            log.error("redisScript init lua error",e);
        }
    }
    
    /**
     * 执行节目全量删除的 Lua 脚本。
     *
     * @param keys Redis key 列表（节目相关所有 key 的集合）
     * @param args Lua 脚本参数
     */
    public void del(List<String> keys, String[] args){
        redisCache.getInstance().execute(redisScript, keys, args);
    }
}
