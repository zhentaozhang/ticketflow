package com.ticketflow.servicelock;

/**
 * 分布式锁类型枚举。
 *
 * Reentrant — 可重入锁（默认），Fair — 公平锁，Read — 读锁，Write — 写锁
 */
public enum LockType {
    /**
     * 可重入锁
     */
    Reentrant,
    /**
     * 公平锁
     */
    Fair,
    /**
     * 读锁
     */
    Read,
    /**
     * 写锁
     */
    Write;

    LockType() {
    }

}
