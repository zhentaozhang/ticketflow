package com.ticketflow.lockinfo.impl;

import com.ticketflow.lockinfo.AbstractLockInfoHandle;

/**
 * 防重复执行（@RepeatExecuteLimit）的锁信息处理器。
 *
 * 锁名前缀 = "REPEAT_EXECUTE_LIMIT"，确保锁名不与其他业务锁冲突
 */
public class RepeatExecuteLimitLockInfoHandle extends AbstractLockInfoHandle {

    public static final String PREFIX_NAME = "REPEAT_EXECUTE_LIMIT";
    
    @Override
    protected String getLockPrefixName() {
        return PREFIX_NAME;
    }
}
