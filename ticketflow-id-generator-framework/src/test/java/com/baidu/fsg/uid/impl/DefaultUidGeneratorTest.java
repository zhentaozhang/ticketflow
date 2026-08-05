package com.baidu.fsg.uid.impl;

import com.baidu.fsg.uid.worker.WorkerIdAssigner;
import com.ticketflow.toolkit.SnowflakeIdGenerator;
import com.ticketflow.toolkit.WorkDataCenterId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultUidGeneratorTest {

    private static final long WORKER_ID = 5L;

    @Mock
    private WorkerIdAssigner workerIdAssigner;

    private DefaultUidGenerator newGenerator() throws Exception {
        when(workerIdAssigner.assignWorkerId()).thenReturn(WORKER_ID);
        DefaultUidGenerator generator = new DefaultUidGenerator();
        generator.setWorkerIdAssigner(workerIdAssigner);
        generator.afterPropertiesSet();
        return generator;
    }

    @Test
    void getUid_bitLayout_matchesConfig() throws Exception {
        DefaultUidGenerator generator = newGenerator();
        for (int i = 0; i < 1000; i++) {
            long uid = generator.getUid();
            assertEquals(WORKER_ID, (uid >> 13) & 0x3FFFFF, "workerId bits polluted");
            assertTrue((uid >> 35) > 0, "timestamp bits invalid");
        }
    }

    @Test
    void getUid_unique_acrossBulk() throws Exception {
        DefaultUidGenerator generator = newGenerator();
        Set<Long> uids = new HashSet<>();
        for (int i = 0; i < 10000; i++) {
            uids.add(generator.getUid());
        }
        assertEquals(10000, uids.size());
    }

    @Test
    void parseUid_roundTripsBits() throws Exception {
        DefaultUidGenerator generator = newGenerator();
        long uid = generator.getUid();
        String parsed = generator.parseUid(uid);
        assertTrue(parsed.contains("\"workerId\":\"5\""));
        assertTrue(parsed.contains("\"UID\":\"" + uid + "\""));
    }

    @Test
    void getOrderNumber_delegatesToSnowflake() throws Exception {
        WorkDataCenterId workDataCenterId = new WorkDataCenterId();
        workDataCenterId.setWorkId(1L);
        workDataCenterId.setDataCenterId(2L);
        DefaultUidGenerator generator = newGenerator();
        generator.setSnowflakeIdGenerator(new SnowflakeIdGenerator(workDataCenterId));
        long orderNumber = generator.getOrderNumber(123L);
        assertEquals(123L & 0x3F, orderNumber & 0x3F, "gene bits mismatch");
        assertEquals(1L, (orderNumber >> 12) & 0x1F, "workerId bits polluted");
    }
}
