package com.ticketflow.service.lua;

import com.alibaba.fastjson.JSON;
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
 * Lua 脚本桥接：创建订单时的座位锁定 + 余票扣减。
 * 加载 programDataCreateOrderResolution.lua，在 Redis 中原子执行：
 * 验证顺序（座位存在/锁定/售出/余票充足/价格一致）→ 锁定座位 → 扣减余票 → 写流水记录。
 *
 * 返回 ProgramCacheCreateOrderData 包含错误码（0=成功，40001~40011=各种失败）和已锁定座位列表
 *
 * 被 BaseProgramOrder.create() 中的 Lua 锁块调用，是并发控制的核心
 */
@Slf4j
@Component
public class ProgramCacheCreateOrderResolutionOperate {
    
    @Autowired
    private RedisCache redisCache;
    
    private DefaultRedisScript<String> redisScript;
    
    /**
     * 加载 Lua 脚本 programDataCreateOrderResolution.lua。
     * 该脚本在高并发下原子执行：验证座位状态 → 锁定座位 → 扣减余票 → 写入流水。
     */
    @PostConstruct
    public void init(){
        try {
            redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/programDataCreateOrderResolution.lua")));
            redisScript.setResultType(String.class);
        } catch (Exception e) {
            log.error("redisScript init lua error",e);
        }
    }
    
    /**
     * 执行创建订单的 Lua 脚本，返回锁定结果。
     *
     * @param keys Redis key 列表（节目座位/余票/流水等 Hash key）
     * @param args Lua 脚本参数（座位 ID、余票数量、价格等）
     * @return 包含错误码（0=成功）和已锁定座位列表的结果对象
     */
    public ProgramCacheCreateOrderData programCacheOperate(List<String> keys, String[] args){
        Object object = redisCache.getInstance().execute(redisScript, keys, args);
        return JSON.parseObject((String)object, ProgramCacheCreateOrderData.class);
    }
}
