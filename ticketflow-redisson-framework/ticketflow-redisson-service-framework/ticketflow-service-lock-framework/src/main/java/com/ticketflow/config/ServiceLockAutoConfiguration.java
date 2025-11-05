package com.ticketflow.config;

import com.ticketflow.constant.LockInfoType;
import com.ticketflow.core.ManageLocker;
import com.ticketflow.lockinfo.LockInfoHandle;
import com.ticketflow.lockinfo.factory.LockInfoHandleFactory;
import com.ticketflow.lockinfo.impl.ServiceLockInfoHandle;
import com.ticketflow.servicelock.aspect.ServiceLockAspect;
import com.ticketflow.servicelock.factory.ServiceLockFactory;
import com.ticketflow.util.ServiceLockTool;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;

/**
 * 分布式锁自动配置——注册 ManageLocker / ServiceLockFactory / ServiceLockAspect / ServiceLockTool。
 *
 * 按 LockInfoType.SERVICE_LOCK 注册 ServiceLockInfoHandle 到 LockInfoHandleFactory
 */
public class ServiceLockAutoConfiguration {
    
    @Bean(LockInfoType.SERVICE_LOCK)
    public LockInfoHandle serviceLockInfoHandle(){
        return new ServiceLockInfoHandle();
    }
    
    @Bean
    public ManageLocker manageLocker(RedissonClient redissonClient){
        return new ManageLocker(redissonClient);
    }
    
    @Bean
    public ServiceLockFactory serviceLockFactory(ManageLocker manageLocker){
        return new ServiceLockFactory(manageLocker);
    }
    
    @Bean
    public ServiceLockAspect serviceLockAspect(LockInfoHandleFactory lockInfoHandleFactory,ServiceLockFactory serviceLockFactory){
        return new ServiceLockAspect(lockInfoHandleFactory,serviceLockFactory);
    }
    
    @Bean
    public ServiceLockTool serviceLockUtil(LockInfoHandleFactory lockInfoHandleFactory,ServiceLockFactory serviceLockFactory){
        return new ServiceLockTool(lockInfoHandleFactory,serviceLockFactory);
    }
}
