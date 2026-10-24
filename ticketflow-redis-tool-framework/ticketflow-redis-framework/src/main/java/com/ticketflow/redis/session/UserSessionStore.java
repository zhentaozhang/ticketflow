package com.ticketflow.redis.session;

import com.alibaba.fastjson.JSON;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.session.SessionSigner;
import com.ticketflow.session.UserSessionVo;
import com.ticketflow.util.StringUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * 登录会话存储（读写 + 验签 + 失效）。
 * <p>
 * 写入方：user-service 登录成功后调用 {@link #save}。
 * 读取方：gateway 的 TokenService 在每次受保护请求上调用 {@link #loadPayload}。
 * <p>
 * 与会话 key 直接存用户快照的区别：值外层包 {@link UserSessionVo} 并附带 HMAC 签名，
 * 读取时校验签名，使「Redis key 存在」不再等价于「会话合法」。
 */
@Slf4j
public class UserSessionStore {

    private final RedisCache redisCache;

    private final String signSecret;

    public UserSessionStore(RedisCache redisCache, String signSecret) {
        this.redisCache = redisCache;
        this.signSecret = signSecret;
    }

    /**
     * 写入会话。
     *
     * @param code          渠道 code
     * @param userId        用户 id
     * @param sessionObject 会话负载对象（user-service 传入脱敏后的 User）
     * @param ttl           过期时间
     * @param timeUnit      时间单位
     */
    public void save(String code, Long userId, Object sessionObject, long ttl, TimeUnit timeUnit) {
        String payload = JSON.toJSONString(sessionObject);
        long issuedAt = System.currentTimeMillis();
        UserSessionVo sessionVo = new UserSessionVo();
        sessionVo.setUserId(String.valueOf(userId));
        sessionVo.setCode(code);
        sessionVo.setIssuedAt(issuedAt);
        sessionVo.setPayload(payload);
        sessionVo.setSign(SessionSigner.sign(signSecret, String.valueOf(userId), code, issuedAt, payload));
        redisCache.set(buildKey(code, userId), sessionVo, ttl, timeUnit);
    }

    /**
     * 读取并校验会话，返回原始会话结构；不存在或签名不合法返回 null。
     */
    public UserSessionVo load(String code, Long userId) {
        if (StringUtil.isEmpty(code) || userId == null) {
            return null;
        }
        String raw = redisCache.get(buildKey(code, userId), String.class);
        if (StringUtil.isEmpty(raw)) {
            return null;
        }
        UserSessionVo sessionVo;
        try {
            sessionVo = JSON.parseObject(raw, UserSessionVo.class);
        } catch (Exception e) {
            log.warn("会话值解析失败，视为未登录 code={} userId={}", code, userId, e);
            return null;
        }
        if (sessionVo == null || sessionVo.getPayload() == null) {
            return null;
        }
        // key 中的 userId/code 必须与负载一致，避免把 A 用户/渠道的会话值搬到 B 的 key 上复用
        if (!String.valueOf(userId).equals(sessionVo.getUserId()) || !code.equals(sessionVo.getCode())) {
            log.warn("会话 userId/code 与 key 不一致，视为未登录 code={} userId={}", code, userId);
            return null;
        }
        if (!SessionSigner.verify(signSecret, sessionVo.getUserId(), sessionVo.getCode(),
                sessionVo.getIssuedAt(), sessionVo.getPayload(), sessionVo.getSign())) {
            log.warn("会话签名校验失败，视为未登录 code={} userId={}", code, userId);
            return null;
        }
        return sessionVo;
    }

    /**
     * 读取并反序列化会话负载；不存在或签名不合法返回 null。
     */
    public <T> T loadPayload(String code, Long userId, Class<T> clazz) {
        UserSessionVo sessionVo = load(code, userId);
        if (sessionVo == null) {
            return null;
        }
        try {
            return JSON.parseObject(sessionVo.getPayload(), clazz);
        } catch (Exception e) {
            log.warn("会话负载反序列化失败 code={} userId={}", code, userId, e);
            return null;
        }
    }

    /**
     * 使会话立即失效（改密/换绑手机/换绑邮箱后强制重新登录）。
     */
    public void invalidate(String code, Long userId) {
        if (StringUtil.isEmpty(code) || userId == null) {
            return;
        }
        redisCache.del(buildKey(code, userId));
    }

    private RedisKeyBuild buildKey(String code, Long userId) {
        return RedisKeyBuild.createRedisKey(RedisKeyManage.USER_LOGIN, code, String.valueOf(userId));
    }
}
