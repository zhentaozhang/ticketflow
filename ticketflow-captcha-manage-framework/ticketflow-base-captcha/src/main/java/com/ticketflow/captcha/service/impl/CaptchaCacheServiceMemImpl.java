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

    @Override
    public Long increment(String key, long val) {
        String current = get(key);
        long ret = Long.parseLong(current == null ? "0" : current) + val;
        set(key, String.valueOf(ret), 0);
        return ret;
    }

    @Override
    public String type() {
        return "local";
    }
}
