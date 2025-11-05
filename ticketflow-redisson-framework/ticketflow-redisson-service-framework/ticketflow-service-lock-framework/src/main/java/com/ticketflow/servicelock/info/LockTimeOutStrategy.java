package com.ticketflow.servicelock.info;


/**
 * 分布式锁超时策略——目前仅 FAIL（快速失败）一种。
 *
 * 实现 LockTimeOutHandler 接口，在锁等待超时时抛出 RuntimeException
 */
public enum LockTimeOutStrategy implements LockTimeOutHandler{
    /**
     * 快速失败
     * */
    FAIL(){
        @Override
        public void handler(String lockName) {
            String msg = String.format("%s请求频繁",lockName);
            throw new RuntimeException(msg);
        }
    }
}
