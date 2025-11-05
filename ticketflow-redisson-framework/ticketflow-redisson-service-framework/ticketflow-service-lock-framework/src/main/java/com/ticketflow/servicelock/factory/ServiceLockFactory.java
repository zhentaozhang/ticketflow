package com.ticketflow.servicelock.factory;

import com.ticketflow.core.ManageLocker;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.servicelock.ServiceLocker;
import lombok.AllArgsConstructor;

/**
 * 分布式锁类型工厂——策略模式。
 *
 * 根据 LockType 从 ManageLocker 缓存中选取对应的 ServiceLocker 实现。
 * 调用方（@ServiceLock 切面 / ServiceLockTool）只需传入 LockType，
 * 无需关心锁的具体实现（Fair/Reentrant/Read/Write）。
 **/
@AllArgsConstructor
public class ServiceLockFactory {
    
    private final ManageLocker manageLocker;
    

    public ServiceLocker getLock(LockType lockType){
        ServiceLocker lock;
        switch (lockType) {
            case Fair:
                lock = manageLocker.getFairLocker();
                break;
            case Write:
                lock = manageLocker.getWriteLocker();
                break;
            case Read:
                lock = manageLocker.getReadLocker();
                break;
            default:
                lock = manageLocker.getReentrantLocker();
                break;
        }
        return lock;
    }
}
