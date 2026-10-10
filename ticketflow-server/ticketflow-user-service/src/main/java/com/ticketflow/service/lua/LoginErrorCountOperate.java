package com.ticketflow.service.lua;

import com.ticketflow.initialize.base.AbstractApplicationPostConstructHandler;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Lua 脚本桥接：登录失败次数原子累加。
 * 加载 loginErrorCount.lua，将 INCR 与 EXPIRE 合并为一次原子操作，
 * 避免两条独立命令之间 TTL 丢失导致计数永不过期（登录被永久锁定）。
 */
@Slf4j
@Component
public class LoginErrorCountOperate extends AbstractApplicationPostConstructHandler {

    @Autowired
    private RedisCache redisCache;

    private DefaultRedisScript<Long> redisScript;

    @Override
    public Integer executeOrder() {
        return 1;
    }

    @Override
    public void executeInit(final ConfigurableApplicationContext context) {
        try {
            redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/loginErrorCount.lua")));
            redisScript.setResultType(Long.class);
        } catch (Exception e) {
            log.error("redisScript init lua error", e);
        }
    }

    public Long loginErrorCount(RedisKeyBuild key, long expireSeconds) {
        Object result = redisCache.getInstance().execute(redisScript, List.of(key.getRelKey()), String.valueOf(expireSeconds));
        return result == null ? 0L : Long.parseLong(String.valueOf(result));
    }
}
