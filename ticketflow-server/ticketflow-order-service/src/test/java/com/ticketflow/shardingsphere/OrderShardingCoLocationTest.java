package com.ticketflow.shardingsphere;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单库分片配置的"同库"约束测试。
 * <p>
 * 为什么需要它：建单要在<b>一个本地事务</b>里写订单、购票人、购票人流水、订单节目关联四张表，
 * 所以这四张表必须落在同一个<b>库</b>上。
 * 而库索引的位移量是由库算法里的 {@code table-sharding-count} 决定的——
 * 只要某张表这个值配得和别的表不一样，同一个订单号就会算出不同的库，本地事务就不成立了，
 * 而且这种错配在功能测试里完全看不出来（单库环境、或者数据量小时都不会暴露）。
 * <p>
 * 这个测试把所有订单分片配置都读出来算一遍：参数必须一致，
 * 且用这套参数对全部 64 种基因取值算出的库索引必须相同。
 * <p>
 * 注意它检查的是"所有配置"而不是"某一份"：订单分片配置有本地用的和集成测试用的两份，
 * 当初两份都错——只查一份的话，另一份里同样的问题会活下来。
 */
class OrderShardingCoLocationTest {

    /**
     * 所有订单分片配置。新增配置时要加到这里，否则它就不在护栏里。
     */
    private static final List<String> CONFIG_FILES = List.of(
            "shardingsphere-order-local.yaml",
            "shardingsphere-order-it.yaml");

    /** 建单事务里一起写的四张表 */
    private static final List<String> ORDER_TABLES = List.of(
            "d_order",
            "d_order_ticket_user",
            "d_order_ticket_user_record",
            "d_order_program");

    @Test
    void 订单相关四张表的库索引位移量必须一致() {
        for (String configFile : CONFIG_FILES) {
            Map<String, Object> shardingRule = loadShardingRule(configFile);
            Map<String, Map<String, Object>> tables = section(shardingRule, "tables");
            Map<String, Map<String, Object>> algorithms = section(shardingRule, "shardingAlgorithms");

            int dbCount = -1;
            int expectTableShardingCount = -1;
            for (String table : ORDER_TABLES) {
                Map<String, Object> tableConfig = tables.get(table);
                assertNotNull(tableConfig, configFile + " 配置里缺少表: " + table);
                Map<String, Object> props = algorithmProps(algorithms, databaseAlgorithmName(tableConfig));
                int tableShardingCount = (int) props.get("table-sharding-count");
                int shardingCount = (int) props.get("sharding-count");
                if (dbCount < 0) {
                    dbCount = shardingCount;
                    expectTableShardingCount = tableShardingCount;
                    continue;
                }
                assertEquals(dbCount, shardingCount,
                        configFile + "：" + table + " 的分库数与另外三张订单表不一致，它们不可能落在同一个库");
                assertEquals(expectTableShardingCount, tableShardingCount,
                        configFile + "：" + table + " 的库算法位移量（table-sharding-count）与另外三张订单表不一致，"
                                + "同一个订单号会算出不同的库，建单的本地事务不成立");
            }
        }
    }

    @Test
    void 任意订单号算出的库索引必须相同() {
        for (String configFile : CONFIG_FILES) {
            Map<String, Object> shardingRule = loadShardingRule(configFile);
            Map<String, Map<String, Object>> tables = section(shardingRule, "tables");
            Map<String, Map<String, Object>> algorithms = section(shardingRule, "shardingAlgorithms");

            // 基因只有 6 位，所以把全部 64 种取值都跑一遍
            for (long key = 0; key < 64; key++) {
                Long expectIndex = null;
                for (String table : ORDER_TABLES) {
                    Map<String, Object> props = algorithmProps(algorithms, databaseAlgorithmName(tables.get(table)));
                    long index = ShardingGeneUtils.databaseIndex(
                            (int) props.get("sharding-count"), key, (int) props.get("table-sharding-count"));
                    if (expectIndex == null) {
                        expectIndex = index;
                        continue;
                    }
                    assertEquals(expectIndex.longValue(), index,
                            configFile + "：订单号 " + key + " 下 " + table + " 会落到不同的库");
                }
                assertNotNull(expectIndex);
            }
            assertTrue(ORDER_TABLES.size() > 1);
        }
    }

    // ==================== 配置读取 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadShardingRule(String configFile) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(configFile)) {
            assertNotNull(in, "读不到分片配置: " + configFile);
            // ShardingSphere 用自定义局部标签（`- !SHARDING`）标记分片规则，原生 SnakeYAML 解析不了，
            // 所以先把标签去掉再当作普通 map 读。这样测试只依赖配置结构，不依赖 SnakeYAML 内部实现。
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("(?m)^(\\s*-\\s+)!\\S+", "$1");
            Map<String, Object> root = new Yaml().load(text);
            List<Map<String, Object>> rules = (List<Map<String, Object>>) root.get("rules");
            assertNotNull(rules, configFile + " 里没有 rules");
            return rules.get(0);
        } catch (Exception e) {
            throw new IllegalStateException("解析分片配置失败: " + configFile, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> section(Map<String, Object> shardingRule, String key) {
        Map<String, Map<String, Object>> section = (Map<String, Map<String, Object>>) shardingRule.get(key);
        assertNotNull(section, "分片配置里缺少 " + key);
        return section;
    }

    @SuppressWarnings("unchecked")
    private String databaseAlgorithmName(Map<String, Object> tableConfig) {
        Map<String, Object> databaseStrategy = (Map<String, Object>) tableConfig.get("databaseStrategy");
        assertNotNull(databaseStrategy, "表缺少 databaseStrategy");
        Map<String, Object> complex = (Map<String, Object>) databaseStrategy.get("complex");
        assertNotNull(complex, "只支持 complex 分片策略");
        return (String) complex.get("shardingAlgorithmName");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> algorithmProps(Map<String, Map<String, Object>> algorithms, String algorithmName) {
        Map<String, Object> algorithm = algorithms.get(algorithmName);
        assertNotNull(algorithm, "配置里缺少分片算法: " + algorithmName);
        Map<String, Object> props = (Map<String, Object>) algorithm.get("props");
        assertNotNull(props, "分片算法缺少 props: " + algorithmName);
        return props;
    }
}
