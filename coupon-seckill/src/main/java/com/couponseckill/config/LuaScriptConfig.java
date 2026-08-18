package com.couponseckill.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * Lua 脚本预加载（脚本外置 classpath:lua/*.lua，对齐现有项目风格）。
 */
@Configuration
public class LuaScriptConfig {

    public static final String GRAB_SCRIPT = "flashGrabScript";
    public static final String ROLLBACK_SCRIPT = "flashRollbackScript";

    @Bean(GRAB_SCRIPT)
    public DefaultRedisScript<Long> flashGrabScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/grab.lua")));
        script.setResultType(Long.class);
        return script;
    }

    @Bean(ROLLBACK_SCRIPT)
    public DefaultRedisScript<Long> flashRollbackScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/rollback.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
