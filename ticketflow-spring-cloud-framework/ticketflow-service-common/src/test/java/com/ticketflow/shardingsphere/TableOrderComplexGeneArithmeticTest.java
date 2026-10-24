package com.ticketflow.shardingsphere;

import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

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

    @Test
    void doSharding_mobile_routeToSingleTable() {
        String mobile = "13800138000";
        long hash = mobile.hashCode() & 0x7FFFFFFF;
        Collection<String> result = doSharding(colsOfString("mobile", mobile));
        assertEquals(List.of("d_order_" + (hash & 3)), new ArrayList<>(result));
    }

    @Test
    void doSharding_email_routeToSingleTable() {
        String email = "test@ticketflow.com";
        long hash = email.hashCode() & 0x7FFFFFFF;
        Collection<String> result = doSharding(colsOfString("email", email));
        assertEquals(List.of("d_order_" + (hash & 3)), new ArrayList<>(result));
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
    void doSharding_orderNumberIn_routesToEveryMatchingTable() {
        // 回归 P0-1：IN 查询跨基因位时必须 union 路由，只取 findFirst 会静默丢掉其余分表的数据
        List<Long> orderNumbers = List.of(1L << 32 | 0x2AL, 1L << 32 | 0x2BL);
        Collection<String> result = doSharding(colsOfList("order_number", orderNumbers));
        assertEquals(Set.of("d_order_2", "d_order_3"), new HashSet<>(result));
        assertEquals(2, result.size());
    }

    @Test
    void doSharding_userIdIn_sameGene_dedupesToSingleTable() {
        List<Long> userIds = List.of(0x30L, 0x34L);
        Collection<String> result = doSharding(colsOfList("user_id", userIds));
        assertEquals(List.of("d_order_0"), new ArrayList<>(result));
    }

    @Test
    void doSharding_programIdIn_unionAllFourTables() {
        Collection<String> result = doSharding(colsOfList("program_id", List.of(0L, 1L, 2L, 3L)));
        assertEquals(4, result.size());
        assertTrue(result.containsAll(TABLE_NAMES));
    }

    private Map<String, Collection<Long>> colsOfList(String column, List<Long> values) {
        Map<String, Collection<Long>> map = new LinkedHashMap<>();
        map.put(column, values);
        return map;
    }

    @Test
    void doSharding_noShardingKey_returnsAll() {
        Collection<String> result = doSharding(Collections.emptyMap());
        assertTrue(result.containsAll(TABLE_NAMES));
        assertEquals(4, result.size());
    }

    @Test
    void doSharding_eightTables_routesByLow3Bits() {
        // 扩容后 8 表：表基因 = 低 3 位（4 表时 = 低 2 位）
        TableOrderComplexGeneArithmetic algo = new TableOrderComplexGeneArithmetic();
        Properties props = new Properties();
        props.setProperty("sharding-count", "8");
        algo.init(props);
        List<String> names = List.of("d_order_0", "d_order_1", "d_order_2", "d_order_3",
                "d_order_4", "d_order_5", "d_order_6", "d_order_7");
        long orderNumber = 1L << 32 | 0x2AL;
        Collection<String> result = algo.doSharding(names,
                new ComplexKeysShardingValue<>(LOGIC_TABLE, cols("order_number", orderNumber, null), Collections.emptyMap()));
        // 0x2A = 0b101010：低 3 位 = 010 → d_order_2；4 表时低 2 位（0x2A & 3 = 2）同样为 d_order_2，扩容前后表位不变
        assertEquals(List.of("d_order_2"), new ArrayList<>(result));
    }
}