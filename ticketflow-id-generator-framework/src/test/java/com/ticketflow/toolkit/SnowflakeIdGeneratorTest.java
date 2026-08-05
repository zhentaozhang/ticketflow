package com.ticketflow.toolkit;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnowflakeIdGeneratorTest {

    private static final long WORKER_ID = 1L;
    private static final long DATA_CENTER_ID = 2L;
    private static final long USER_ID = 123456789L;
    private static final long BASE_MS = 1700000000000L;

    static class FixedClockSnowflakeIdGenerator extends SnowflakeIdGenerator {

        long currentMs = BASE_MS;

        FixedClockSnowflakeIdGenerator(WorkDataCenterId workDataCenterId) {
            super(workDataCenterId);
        }

        @Override
        protected long timeGen() {
            return currentMs;
        }
    }

    private FixedClockSnowflakeIdGenerator newFixedGenerator(long workId, long dataCenterId) {
        WorkDataCenterId workDataCenterId = new WorkDataCenterId();
        workDataCenterId.setWorkId(workId);
        workDataCenterId.setDataCenterId(dataCenterId);
        return new FixedClockSnowflakeIdGenerator(workDataCenterId);
    }

    @Test
    void nextId_bitLayout_matchesConfig() {
        FixedClockSnowflakeIdGenerator generator = newFixedGenerator(WORKER_ID, DATA_CENTER_ID);
        for (int i = 0; i < 1000; i++) {
            long id = generator.nextId();
            assertEquals(WORKER_ID, (id >> 12) & 0x1F, "workerId bits polluted");
            assertEquals(DATA_CENTER_ID, (id >> 17) & 0x1F, "datacenterId bits polluted");
        }
    }

    @Test
    void nextId_unique_withinSameMillisecond() {
        FixedClockSnowflakeIdGenerator generator = newFixedGenerator(WORKER_ID, DATA_CENTER_ID);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 4000; i++) {
            ids.add(generator.nextId());
        }
        assertEquals(4000, ids.size());
    }

    @Test
    void getOrderNumber_geneBits_keepWorkerAndDatacenterIntact() {
        FixedClockSnowflakeIdGenerator generator = newFixedGenerator(WORKER_ID, DATA_CENTER_ID);
        for (int i = 0; i < 10000; i++) {
            if (i % 60 == 0) {
                generator.currentMs++;
            }
            long orderNumber = generator.getOrderNumber(USER_ID);
            assertEquals(WORKER_ID, (orderNumber >> 12) & 0x1F, "gene sequence polluted workerId bits");
            assertEquals(DATA_CENTER_ID, (orderNumber >> 17) & 0x1F, "gene sequence polluted datacenterId bits");
            assertEquals(USER_ID & 0x3F, orderNumber & 0x3F, "gene bits mismatch");
        }
    }

    @Test
    void getOrderNumber_unique_acrossBulk() {
        FixedClockSnowflakeIdGenerator generator = newFixedGenerator(WORKER_ID, DATA_CENTER_ID);
        Set<Long> orderNumbers = new HashSet<>();
        for (int i = 0; i < 10000; i++) {
            if (i % 60 == 0) {
                generator.currentMs++;
            }
            orderNumbers.add(generator.getOrderNumber(USER_ID));
        }
        assertEquals(10000, orderNumbers.size());
    }

    @Test
    void getOrderNumber_sequenceExhausted_waitsForNextMillisecond() {
        FixedClockSnowflakeIdGenerator generator = newFixedGenerator(WORKER_ID, DATA_CENTER_ID);
        Set<Long> orderNumbers = new HashSet<>();
        for (int i = 0; i < 61; i++) {
            orderNumbers.add(generator.getOrderNumber(USER_ID));
        }
        assertEquals(61, orderNumbers.size(), "same millisecond exhausted before 64 values");
        generator.currentMs++;
        assertTrue(orderNumbers.add(generator.getOrderNumber(USER_ID)), "exhausted sequence reused");
        assertEquals(62, orderNumbers.size());
    }

    @Test
    void constructor_emptyWorkDataCenterId_fallsBackToMacWithinRange() {
        FixedClockSnowflakeIdGenerator generator = new FixedClockSnowflakeIdGenerator(new WorkDataCenterId());
        assertNotNull(generator);
        long id = generator.nextId();
        long macWorkerId = (id >> 12) & 0x1F;
        long macDatacenterId = (id >> 17) & 0x1F;
        assertTrue(macWorkerId >= 0 && macWorkerId <= 31, "fallback workerId out of range: " + macWorkerId);
        assertTrue(macDatacenterId >= 0 && macDatacenterId <= 31, "fallback datacenterId out of range: " + macDatacenterId);
    }

    @Test
    void constructor_maxWorkerId_sequenceNeverPollutesWorkerBits() {
        FixedClockSnowflakeIdGenerator generator = newFixedGenerator(31L, 0L);
        for (int i = 0; i < 10000; i++) {
            if (i % 60 == 0) {
                generator.currentMs++;
            }
            long orderNumber = generator.getOrderNumber(USER_ID);
            assertEquals(31L, (orderNumber >> 12) & 0x1F, "workerId 31 polluted by sequence");
        }
    }
}
