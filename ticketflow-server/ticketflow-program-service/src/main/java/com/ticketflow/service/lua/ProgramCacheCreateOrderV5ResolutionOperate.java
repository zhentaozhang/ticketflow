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
 * Lua 脚本桥接（V5）：创建订单时的幂等 + 座位锁定 + 余票扣减。
 * 加载 programDataCreateOrderResolutionV5.lua，在 Redis 中原子执行：
 * 幂等守卫（SETNX 同一 userId+programId 提交标记）→ 验证座位/余票/价格 → 锁定座位 → 扣减余票 → 写流水。
 *
 * 相比 V4（ProgramCacheCreateOrderResolutionOperate），幂等检查从 Java 侧 @RepeatExecuteLimit 移入 Lua，
 * 请求侧每单只需 1 次 EVAL，不再需要独立幂等 Redis 往返与本地锁。
 */
@Slf4j
@Component
public class ProgramCacheCreateOrderV5ResolutionOperate {

    @Autowired
    private RedisCache redisCache;

    private DefaultRedisScript<String> redisScript;

    /**
     * 加载 Lua 脚本 programDataCreateOrderResolutionV5.lua。
     */
    @PostConstruct
    public void init(){
        try {
            redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/programDataCreateOrderResolutionV5.lua")));
            redisScript.setResultType(String.class);
        } catch (Exception e) {
            log.error("redisScript init v5 lua error",e);
        }
    }

    /**
     * 执行 V5 创建订单的 Lua 脚本，返回锁定结果。
     * code: 0=成功，40035=重复提交，40001~40011=座位/余票/价格失败。
     *
     * @param keys Redis key 列表（含幂等 key，见 lua 脚本 KEYS 注释）
     * @param args Lua 脚本参数（含幂等 TTL，见 lua 脚本 ARGV 注释）
     * @return 包含错误码和已锁定座位列表的结果对象
     */
    public ProgramCacheCreateOrderData programCacheOperate(List<String> keys, String[] args){
        Object object = redisCache.getInstance().execute(redisScript, keys, args);
        return JSON.parseObject((String)object, ProgramCacheCreateOrderData.class);
    }
}
