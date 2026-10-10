package com.ticketflow.captcha.service.impl;

import com.ticketflow.captcha.service.CaptchaCacheService;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地内存验证码缓存。基于ConcurrentHashMap的验证码本地缓存实现。
 **/
public class CaptchaCacheServiceMemImpl implements CaptchaCacheService {

    private final ConcurrentHashMap<String, CacheValue> cache = new ConcurrentHashMap<>();

    private static class CacheValue {
        private final String value;
        private final long expireAt;

        CacheValue(String value, long expiresInSeconds) {
            this.value = value;
            this.expireAt = expiresInSeconds > 0 ? System.currentTimeMillis() + expiresInSeconds * 1000 : Long.MAX_VALUE;
        }

        boolean expired() {
            return expireAt != Long.MAX_VALUE && System.currentTimeMillis() > expireAt;
        }
    }

    @Override
    public void set(String key, String value, long expiresInSeconds) {
        cache.put(key, new CacheValue(value, expiresInSeconds));
    }

    @Override
    public boolean exists(String key) {
        CacheValue value = cache.get(key);
        if (value == null) {
            return false;
        }
        if (value.expired()) {
            cache.remove(key);
            return false;
        }
        return true;
    }

    @Override
    public void delete(String key) {
        cache.remove(key);
    }

    @Override
    public String get(String key) {
        CacheValue value = cache.get(key);
        if (value == null) {
            return null;
        }
        if (value.expired()) {
            cache.remove(key);
            return null;
        }
        return value.value;
    }

    /**
     * 无既有键时 increment 使用的默认窗口（秒）。
     * 绝不能传 0：CacheValue 里 expiresInSeconds<=0 表示"永不过期"，
     * 会把 set(...,60) 建立的分钟窗口计数变成永久键（限流/锁定计数越积越多）。
     */
    private static final long DEFAULT_INCREMENT_TTL_SECONDS = 60L;

    @Override
    public Long increment(String key, long val) {
        // compute 原子更新，并保留既有键的剩余 TTL；无键则用默认窗口，保证计数键最终会过期
        CacheValue updated = cache.compute(key, (k, existing) -> {
            long base = 0L;
            long ttlSeconds = DEFAULT_INCREMENT_TTL_SECONDS;
            if (existing != null && !existing.expired()) {
                base = Long.parseLong(existing.value);
                long remainingMs = existing.expireAt - System.currentTimeMillis();
                ttlSeconds = Math.max(1L, remainingMs / 1000);
            }
            return new CacheValue(String.valueOf(base + val), ttlSeconds);
        });
        return Long.parseLong(updated.value);
    }

    @Override
    public String type() {
        return "local";
    }
}
