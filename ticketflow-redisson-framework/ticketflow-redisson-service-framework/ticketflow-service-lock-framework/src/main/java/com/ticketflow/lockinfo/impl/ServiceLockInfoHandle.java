package com.ticketflow.lockinfo.impl;

import com.ticketflow.lockinfo.AbstractLockInfoHandle;

/**
 * 分布式锁（@ServiceLock）的锁信息处理器。
 *
 * 锁名前缀 = "SERVICE_LOCK"，与 @RepeatExecuteLimit 使用的 "REPEAT_EXECUTE_LIMIT" 隔离
 */
public class ServiceLockInfoHandle extends AbstractLockInfoHandle {

    private static final String LOCK_PREFIX_NAME = "SERVICE_LOCK";
    
    @Override
    protected String getLockPrefixName() {
        return LOCK_PREFIX_NAME;
    }
}
