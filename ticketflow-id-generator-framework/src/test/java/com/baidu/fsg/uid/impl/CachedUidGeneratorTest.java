package com.baidu.fsg.uid.impl;

import com.baidu.fsg.uid.worker.WorkerIdAssigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CachedUidGeneratorTest {

    private static final long WORKER_ID = 5L;

    @Mock
    private WorkerIdAssigner workerIdAssigner;

    @Test
    void getUid_unique_acrossBulk() throws Exception {
        when(workerIdAssigner.assignWorkerId()).thenReturn(WORKER_ID);
        CachedUidGenerator generator = new CachedUidGenerator();
        generator.setWorkerIdAssigner(workerIdAssigner);
        generator.afterPropertiesSet();
        try {
            Set<Long> uids = ConcurrentHashMap.newKeySet();
            for (int i = 0; i < 10000; i++) {
                uids.add(generator.getUid());
            }
            assertEquals(10000, uids.size());
            for (long uid : uids) {
                assertEquals(WORKER_ID, (uid >> 13) & 0x3FFFFF, "workerId bits polluted");
            }
        } finally {
            generator.destroy();
        }
    }

    @Test
    void getUid_multithreaded_unique() throws Exception {
        when(workerIdAssigner.assignWorkerId()).thenReturn(WORKER_ID);
        CachedUidGenerator generator = new CachedUidGenerator();
        generator.setWorkerIdAssigner(workerIdAssigner);
        generator.afterPropertiesSet();
        try {
            int threads = 8;
            int perThread = 2000;
            Set<Long> all = ConcurrentHashMap.newKeySet();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            all.add(generator.getUid());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "generation timed out");
            assertEquals(threads * perThread, all.size());
        } finally {
            generator.destroy();
        }
    }
}
