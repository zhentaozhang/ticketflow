package com.ticketflow.config;

import com.ticketflow.constant.LockInfoType;
import com.ticketflow.handle.RedissonDataHandle;
import com.ticketflow.locallock.LocalLockCache;
import com.ticketflow.lockinfo.LockInfoHandle;
import com.ticketflow.lockinfo.factory.LockInfoHandleFactory;
import com.ticketflow.lockinfo.impl.RepeatExecuteLimitLockInfoHandle;
import com.ticketflow.repeatexecutelimit.aspect.RepeatExecuteLimitAspect;
import com.ticketflow.servicelock.factory.ServiceLockFactory;
import org.springframework.context.annotation.Bean;

/**
 * 防重复执行自动配置——注册 RepeatExecuteLimitAspect。
 * <p>
 * 按 LockInfoType.REPEAT_EXECUTE_LIMIT 注册 RepeatExecuteLimitLockInfoHandle
 * 到 LockInfoHandleFactory，同时注入 LocalLockCache / RedissonDataHandle
 */
public class RepeatExecuteLimitAutoConfiguration {

    @Bean(LockInfoType.REPEAT_EXECUTE_LIMIT)
    public LockInfoHandle repeatExecuteLimitHandle() {
        return new RepeatExecuteLimitLockInfoHandle();
    }

    @Bean
    public RepeatExecuteLimitAspect repeatExecuteLimitAspect(LocalLockCache localLockCache,
                                                             LockInfoHandleFactory lockInfoHandleFactory,
                                                             ServiceLockFactory serviceLockFactory,
                                                             RedissonDataHandle redissonDataHandle) {
        return new RepeatExecuteLimitAspect(localLockCache, lockInfoHandleFactory, serviceLockFactory, redissonDataHandle);
    }
}
    