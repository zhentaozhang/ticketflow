package com.ticketflow.redis.config;

import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisCacheImpl;
import com.ticketflow.redis.session.UserSessionStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis缓存自动配置。将RedisCacheImpl注册为Spring Bean，提供统一缓存操作入口。
 **/
@Slf4j
public class RedisCacheAutoConfig {

    /**
     * 本地/默认签名密钥。生产环境必须通过 SESSION_SIGN_SECRET（或 jasypt 加密配置）覆盖，
     * 否则会话签名可被已知密钥伪造。
     */
    private static final String DEV_DEFAULT_SESSION_SECRET = "ticketflow-dev-session-secret";

    @Bean
    public RedisCacheImpl redisCache(@Qualifier("redisToolStringRedisTemplate") StringRedisTemplate stringRedisTemplate){
        return new RedisCacheImpl(stringRedisTemplate);
    }

    /**
     * 登录会话读写器：写入时对会话负载签名，读取时验签，避免「Redis key 存在」被当作合法登录态。
     */
    @Bean
    public UserSessionStore userSessionStore(RedisCache redisCache,
                                             @Value("${ticketflow.session.sign-secret:" + DEV_DEFAULT_SESSION_SECRET + "}") String signSecret) {
        if (signSecret == null || signSecret.trim().isEmpty()) {
            throw new IllegalStateException("ticketflow.session.sign-secret 不能为空：会话签名密钥缺失会导致登录态无法校验");
        }
        if (DEV_DEFAULT_SESSION_SECRET.equals(signSecret)) {
            log.warn("会话签名使用内置默认密钥（仅限本地开发）：请用 SESSION_SIGN_SECRET 覆盖后再部署");
        }
        return new UserSessionStore(redisCache, signSecret);
    }
}
