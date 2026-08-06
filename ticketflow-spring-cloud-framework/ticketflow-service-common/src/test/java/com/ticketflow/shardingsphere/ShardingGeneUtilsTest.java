package com.ticketflow.shardingsphere;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShardingGeneUtilsTest {

    @Test
    void checkPowerOfTwo_powerOfTwoValues_pass() {
        ShardingGeneUtils.checkPowerOfTwo(1, "sharding-count");
        ShardingGeneUtils.checkPowerOfTwo(2, "sharding-count");
        ShardingGeneUtils.checkPowerOfTwo(4, "sharding-count");
        ShardingGeneUtils.checkPowerOfTwo(8, "sharding-count");
        ShardingGeneUtils.checkPowerOfTwo(64, "sharding-count");
    }

    @Test
    void checkPowerOfTwo_nonPowerOfTwo_throws() {
        assertThrows(IllegalArgumentException.class, () -> ShardingGeneUtils.checkPowerOfTwo(0, "sharding-count"));
        assertThrows(IllegalArgumentException.class, () -> ShardingGeneUtils.checkPowerOfTwo(3, "sharding-count"));
        assertThrows(IllegalArgumentException.class, () -> ShardingGeneUtils.checkPowerOfTwo(6, "sharding-count"));
        assertThrows(IllegalArgumentException.class, () -> ShardingGeneUtils.checkPowerOfTwo(-2, "sharding-count"));
    }

    @Test
    void tableGeneLength_returnsLog2OfCount() {
        assertEquals(1, ShardingGeneUtils.tableGeneLength(2));
        assertEquals(2, ShardingGeneUtils.tableGeneLength(4));
        assertEquals(3, ShardingGeneUtils.tableGeneLength(8));
        assertEquals(6, ShardingGeneUtils.tableGeneLength(64));
    }

    @Test
    void tableIndex_matchesLowBitFormula() {
        for (int tableCount : new int[]{2, 4, 8}) {
            for (long key = 0; key < 1024; key++) {
                long expected = (tableCount - 1) & key;
                assertEquals(expected, ShardingGeneUtils.tableIndex(tableCount, key),
                        "tableCount=" + tableCount + " key=" + key);
            }
        }
    }

    @Test
    void databaseIndex_matchesOldFormula() {
        // 旧实现: (databaseCount - 1) & (key >> (long)(Math.log(tableCount)/Math.log(2)))
        for (int dbCount : new int[]{2, 4}) {
            for (int tableCount : new int[]{2, 4, 8}) {
                for (long key = 0; key < 1024; key++) {
                    long expected = (dbCount - 1) & (key >> (long) (Math.log(tableCount) / Math.log(2)));
                    assertEquals(expected, ShardingGeneUtils.databaseIndex(dbCount, key, tableCount),
                            "dbCount=" + dbCount + " tableCount=" + tableCount + " key=" + key);
                }
            }
        }
    }

    @Test
    void databaseIndex_noFloatPrecisionBugForLargePowerOfTwo() {
        // 经典浮点坑: Math.log(1024)/Math.log(2) 可能 = 9.999999...，强转后得到 9 而非 10
        // tableGeneLength(1024) 必须精确为 10（位数），否则 key>>9 与 key>>10 结果不同
        assertEquals(10, ShardingGeneUtils.tableGeneLength(1024));
        long key = (1L << 11) | (1L << 10) | 5L;
        // 正确（10 位表基因）: key>>10=0b11=3 → (8-1)&3=3；错误（9 位）: key>>9=0b1101=13 → 7&13=5≠3
        assertEquals(3, ShardingGeneUtils.databaseIndex(8, key, 1024));
    }
}
