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

class TableOrderComplexGeneArithmeticTest {

    private static final String LOGIC_TABLE = "d_order";
    private static final List<String> TABLE_NAMES = List.of("d_order_0", "d_order_1", "d_order_2", "d_order_3");

    private TableOrderComplexGeneArithmetic algorithm;

    @BeforeEach
    void setUp() {
        algorithm = new TableOrderComplexGeneArithmetic();
        Properties props = new Properties();
        props.setProperty("sharding-count", "4");
        algorithm.init(props);
    }

    private Collection<String> doSharding(Map<String, Collection<Long>> cols) {
        return algorithm.doSharding(TABLE_NAMES, new ComplexKeysShardingValue<>(LOGIC_TABLE, cols, Collections.emptyMap()));
    }

    @Test
    void doSharding_outOrderNoString_routeToSameTableAsOrderNumber() {
        // 支付表分片列为 out_order_no（订单号字符串），路由结果必须与 order_number 完全一致
        long gene = 0x2AL;
        long orderNumber = 1L << 32 | gene;
        String outOrderNo = String.valueOf(orderNumber);
        Collection<String> result = doSharding(colsOfString("out_order_no", outOrderNo));
        assertEquals(List.of("d_order_" + (orderNumber & 3)), new ArrayList<>(result));
    }

    private Map<String, Collection<Long>> colsOfString(String column, String value) {
        Map<String, Collection<Long>> map = new LinkedHashMap<>();
        map.put(column, (Collection<Long>) (Collection<?>) List.of(value));
        return map;
    }

    private Map<String, Collection<Long>> cols(String column, Long value, Long secondValue) {
        Map<String, Collection<Long>> map = new LinkedHashMap<>();
        map.put(column, List.of(value));
        if (secondValue != null) {
            map.put("user_id", List.of(secondValue));
        }
        return map;
    }

    @Test
    void init_nonPowerOfTwo_throws() {
        TableOrderComplexGeneArithmetic algo = new TableOrderComplexGeneArithmetic();
        Properties props = new Properties();
        props.setProperty("sharding-count", "3");
        assertThrows(IllegalArgumentException.class, () -> algo.init(props));
    }

    @Test
    void doSharding_orderNumber_routeToSingleTable() {
        long gene = 0x2AL; // (0x2A & 3) = 2
        long orderNumber = 1L << 32 | gene;
        Collection<String> result = doSharding(cols("order_number", orderNumber, null));
        assertEquals(List.of("d_order_2"), new ArrayList<>(result));
    }

    @Test
    void doSharding_userId_routeToSingleTable() {
        long userId = 0x30L; // (0x30 & 3) = 0
        Collection<String> result = doSharding(cols("user_id", userId, null));
        assertEquals(List.of("d_order_0"), new ArrayList<>(result));
    }

    @Test
    void doSharding_orderNumberAndUserId_sameGene_notThrows() {
        long gene = 0x2AL;
        long orderNumber = 1L << 32 | gene;
        Collection<String> result = doSharding(cols("order_number", orderNumber, gene));
        assertEquals(List.of("d_order_" + (gene & 3)), new ArrayList<>(result));
    }

    @Test
    void doSharding_orderNumberAndUserId_differentGene_throws() {
        long orderNumber = 1L << 32 | 0x00L;
        long userId = 0x03L;
        assertThrows(IllegalArgumentException.class, () -> doSharding(cols("order_number", orderNumber, userId)));
    }

    @Test
    void doSharding_noShardingKey_returnsAll() {
        Collection<String> result = doSharding(Collections.emptyMap());
        assertTrue(result.containsAll(TABLE_NAMES));
        assertEquals(4, result.size());
    }

    @Test
    void doSharding_eightTables_routesByLow3Bits() {
        // 扩容后 8 表：表基因 = 低 3 位（4 表时 = 低 2 位），相同 order 号路由结果不变
        TableOrderComplexGeneArithmetic algo = new TableOrderComplexGeneArithmetic();
        Properties props = new Properties();
        props.setProperty("sharding-count", "8");
        algo.init(props);
        List<String> names = List.of("d_order_0", "d_order_1", "d_order_2", "d_order_3",
                "d_order_4", "d_order_5", "d_order_6", "d_order_7");
        long orderNumber = 1L << 32 | 0x2AL;
        Collection<String> result = algo.doSharding(names,
                new ComplexKeysShardingValue<>(LOGIC_TABLE, cols("order_number", orderNumber, null), Collections.emptyMap()));
        assertEquals(List.of("d_order_2"), new ArrayList<>(result));
        // 6 表场景在 4 表下不存在，扩容前路由为 d_order_2（0x2A & 3），扩容后仍为 d_order_2（0x2A & 7 = 2）
    }
}