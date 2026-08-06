package com.ticketflow.servicelock.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedissonReentrantLockerTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    @Test
    void tryLockShouldRestoreInterruptStatusWhenInterrupted() throws InterruptedException {
        when(redissonClient.getLock("test-lock")).thenReturn(lock);
        when(lock.tryLock(1, TimeUnit.SECONDS)).thenThrow(new InterruptedException());

        RedissonReentrantLocker locker = new RedissonReentrantLocker(redissonClient);
        try {
            assertFalse(locker.tryLock("test-lock", TimeUnit.SECONDS, 1));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
