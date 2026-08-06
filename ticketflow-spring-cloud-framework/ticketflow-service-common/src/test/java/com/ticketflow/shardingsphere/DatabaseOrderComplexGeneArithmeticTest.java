package com.ticketflow.shardingsphere;

import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseOrderComplexGeneArithmeticTest {

    private static final String LOGIC_TABLE = "d_order";
    private static final List<String> DB_NAMES = List.of("ds_0", "ds_1");

    private DatabaseOrderComplexGeneArithmetic algorithm;

    @BeforeEach
    void setUp() {
        algorithm = new DatabaseOrderComplexGeneArithmetic();
        Properties props = new Properties();
        props.setProperty("sharding-count", "2");
        props.setProperty("table-sharding-count", "4");
        algorithm.init(props);
    }

    private Collection<String> doSharding(Collection<String> dbNames, Map<String, Collection<Long>> cols) {
        return algorithm.doSharding(dbNames, new ComplexKeysShardingValue<>(LOGIC_TABLE, cols, Collections.emptyMap()));
    }

    private Map<String, Collection<Long>> colsOf(String column, Long value, Long secondValue) {
        Map<String, Collection<Long>> map = new LinkedHashMap<>();
        map.put(column, List.of(value));
        if (secondValue != null) {
            map.put("user_id", List.of(secondValue));
        }
        return map;
    }

    @Test
    void init_nonPowerOfTwo_shardingCount_throws() {
        DatabaseOrderComplexGeneArithmetic algo = new DatabaseOrderComplexGeneArithmetic();
        Properties props = new Properties();
        props.setProperty("sharding-count", "3");
        props.setProperty("table-sharding-count", "4");
        assertThrows(IllegalArgumentException.class, () -> algo.init(props));
    }

    @Test
    void init_nonPowerOfTwo_tableShardingCount_throws() {
        DatabaseOrderComplexGeneArithmetic algo = new DatabaseOrderComplexGeneArithmetic();
        Properties props = new Properties();
        props.setProperty("sharding-count", "2");
        props.setProperty("table-sharding-count", "6");
        assertThrows(IllegalArgumentException.class, () -> algo.init(props));
    }

    @Test
    void doSharding_orderNumber_routeToSingleDb() {
        // order_number 与 user_id 低6位基因一致（0x2A → 0b101010）
        long userId = 0x2AL;
        long orderNumber = 1L << 20 | userId;
        Collection<String> result = doSharding(DB_NAMES, colsOf("order_number", orderNumber, null));
        assertEquals(List.of(expectedDb(orderNumber)), new ArrayList<>(result));
    }

    @Test
    void doSharding_userId_routeToSingleDb() {
        long userId = 0x2AL;
        Collection<String> result = doSharding(DB_NAMES, colsOf("user_id", userId, null));
        assertEquals(List.of(expectedDb(userId)), new ArrayList<>(result));
    }

    @Test
    void doSharding_orderNumberAndUserId_sameGene_notThrows() {
        long gene = 0x2AL;
        long userId = gene;
        long orderNumber = 1L << 32 | gene;
        Collection<String> result = doSharding(DB_NAMES, colsOf("order_number", orderNumber, userId));
        assertEquals(List.of(expectedDb(orderNumber)), new ArrayList<>(result));
    }

    @Test
    void doSharding_orderNumberAndUserId_differentGene_throws() {
        long orderNumber = 1L << 32 | 0x00L;
        long userId = 0x3FL;
        assertThrows(IllegalArgumentException.class, () -> doSharding(DB_NAMES, colsOf("order_number", orderNumber, userId)));
    }

    @Test
    void doSharding_noShardingKey_returnsAll() {
        Collection<String> result = doSharding(DB_NAMES, Collections.emptyMap());
        assertTrue(result.containsAll(DB_NAMES));
        assertEquals(2, result.size());
    }

    @Test
    void doSharding_ds10Prefix_noFalseMatch() {
        // 回归：contains("1") 在 ["ds_0","ds_1","ds_10","ds_11"] 下会误匹配 ds_10，equals 必须精确命中 ds_1
        long gene = 0x3FL; // 低 3 位基因: bit0-1 表基因, bit2 库基因 → (0x3F>>2)&1 = 1 → ds_1
        long orderNumber = 1L << 32 | gene;
        List<String> dbNames = List.of("ds_0", "ds_1", "ds_10", "ds_11");
        Collection<String> result = doSharding(dbNames, cols("order_number", orderNumber, null));
        assertEquals(List.of("ds_1"), new ArrayList<>(result));
    }

    @Test
    void doSharding_outOrderNoString_routeToSameDbAsOrderNumber() {
        // 支付表分片列为 out_order_no（订单号字符串），路由结果必须与 order_number 完全一致
        long gene = 0x2AL;
        long orderNumber = 1L << 32 | gene;
        String outOrderNo = String.valueOf(orderNumber);
        Collection<String> result = doSharding(DB_NAMES, colsOfString("out_order_no", outOrderNo));
        assertEquals(List.of(expectedDb(orderNumber)), new ArrayList<>(result));
    }

    @Test
    void doSharding_outOrderNoNoShardingKey_returnsAll() {
        Collection<String> result = doSharding(DB_NAMES, Collections.emptyMap());
        assertTrue(result.containsAll(DB_NAMES));
        assertEquals(2, result.size());
    }

    private Map<String, Collection<Long>> colsOfString(String column, String value) {
        Map<String, Collection<Long>> map = new LinkedHashMap<>();
        map.put(column, (Collection<Long>) (Collection<?>) List.of(value));
        return map;
    }

    private Map<String, Collection<Long>> cols(String column, Long value, Long secondValue) {
        return colsOf(column, value, secondValue);
    }

    private String expectedDb(long shardingKey) {
        // 2库4表: (2-1) & (key >> 2)
        return "ds_" + ((shardingKey >> 2) & 1);
    }
}