package com.ticketflow.core;

import com.ticketflow.servicelock.LockType;
import com.ticketflow.servicelock.ServiceLocker;
import com.ticketflow.servicelock.impl.RedissonFairLocker;
import com.ticketflow.servicelock.impl.RedissonReadLocker;
import com.ticketflow.servicelock.impl.RedissonReentrantLocker;
import com.ticketflow.servicelock.impl.RedissonWriteLocker;
import org.redisson.api.RedissonClient;

import java.util.HashMap;
import java.util.Map;

import static com.ticketflow.servicelock.LockType.Fair;
import static com.ticketflow.servicelock.LockType.Read;
import static com.ticketflow.servicelock.LockType.Reentrant;
import static com.ticketflow.servicelock.LockType.Write;

/**
 * 分布式锁管理器——启动时创建 4 种 Redisson Locker 并缓存。
 *
 * 4 种锁类型及其适用场景：
 *   ReentrantLock — 默认，可重入，适合普通互斥（订单创建、支付回调与取消互斥）
 *   FairLock      — 公平锁，按请求顺序排队，避免线程饥饿
 *   ReadLock      — 读写锁读锁，可并发读、写互斥（ProgramService.getById 缓存加载）
 *   WriteLock     — 读写锁写锁，写独占（ProgramCategoryService 全量加载写缓存）
 *
 * ServiceLockFactory 通过策略模式选择对应的 locker。
 * 缓存避免每次加锁时重复创建 Redisson 对象。
 **/
public class ManageLocker {

    private final Map<LockType, ServiceLocker> cacheLocker = new HashMap<>();
    
    public ManageLocker(RedissonClient redissonClient){
        cacheLocker.put(Reentrant,new RedissonReentrantLocker(redissonClient));
        cacheLocker.put(Fair,new RedissonFairLocker(redissonClient));
        cacheLocker.put(Write,new RedissonWriteLocker(redissonClient));
        cacheLocker.put(Read,new RedissonReadLocker(redissonClient));
    }
    
    public ServiceLocker getReentrantLocker(){
        return cacheLocker.get(Reentrant);
    }
    
    public ServiceLocker getFairLocker(){
        return cacheLocker.get(Fair);
    }
    
    public ServiceLocker getWriteLocker(){
        return cacheLocker.get(Write);
    }
    
    public ServiceLocker getReadLocker(){
        return cacheLocker.get(Read);
    }
}
