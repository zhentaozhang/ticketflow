package com.ticketflow.captcha.service.impl;

import com.ticketflow.captcha.service.CaptchaCacheService;
import com.ticketflow.captcha.util.CacheUtil;

import java.util.Objects;


/**
 * 本地内存验证码缓存。基于ConcurrentHashMap的验证码本地缓存实现。
 **/
public class CaptchaCacheServiceMemImpl implements CaptchaCacheService {
    @Override
    public void set(String key, String value, long expiresInSeconds) {

        CacheUtil.set(key, value, expiresInSeconds);
    }

    @Override
    public boolean exists(String key) {
        return CacheUtil.exists(key);
    }

    @Override
    public void delete(String key) {
        CacheUtil.delete(key);
    }

    @Override
    public String get(String key) {
        return CacheUtil.get(key);
    }

	@Override
	public Long increment(String key, long val) {
    	Long ret = Long.parseLong(Objects.requireNonNull(CacheUtil.get(key)))+val;
		CacheUtil.set(key, String.valueOf(ret),0);
		return ret;
	}

	@Override
    public String type() {
        return "local";
    }
}
