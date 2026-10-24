package com.ticketflow.shardingsphere;

import cn.hutool.core.collection.CollectionUtil;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingAlgorithm;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * ShardingSphere 自定义复合分片：订单表分表算法（基因法）。
 * 从 orderNumber 中提取分表基因位，决定数据落入哪个物理表，
 * 与分库算法配合实现水平拆分
 */
public class TableOrderComplexGeneArithmetic implements ComplexKeysShardingAlgorithm<Long> {

    /**
     * 属性分表名
     *
     */
    private static final String SHARDING_COUNT_KEY_NAME = "sharding-count";
    /**
     * 分表数量
     *
     */
    private int shardingCount;

    @Override
    public void init(Properties props) {
        shardingCount = Integer.parseInt(props.getProperty(SHARDING_COUNT_KEY_NAME));
        // 位运算分片要求分片数为 2 的幂，配置错误早暴露，避免静默错误路由
        ShardingGeneUtils.checkPowerOfTwo(shardingCount, SHARDING_COUNT_KEY_NAME);
    }

    /**
     * 分表路由逻辑
     * <p>
     * 核心思路：
     * - 取分片键（订单号或userId）的低N位作为表索引
     * - N = log2(表数量)，通过位运算 (shardingCount - 1) & value 实现
     * - 订单号和userId的低6位相同，所以两种查询都能路由到同一张表
     * <p>
     * 基因位分布示例（userId后6位 = [bit5][bit4][bit3][bit2][bit1][bit0]）：
     * - 4表：取bit0-1（2位）
     * - 8表：取bit0-2（3位）
     * - 16表：取bit0-3（4位）
     */
    @Override
    public Collection<String> doSharding(Collection<String> allActualSplitTableNames, ComplexKeysShardingValue<Long> complexKeysShardingValue) {
        //逻辑表名
        String logicTableName = complexKeysShardingValue.getLogicTableName();
        //查询中的列名和值
        Map<String, Collection<Long>> columnNameAndShardingValuesMap = complexKeysShardingValue.getColumnNameAndShardingValuesMap();
        //如果没有条件查询，那么就查所有的分表
        if (CollectionUtil.isEmpty(columnNameAndShardingValuesMap)) {
            return allActualSplitTableNames;
        }
        //order_number条件的值
        Collection<Long> orderNumberValues = columnNameAndShardingValuesMap.get("order_number");
        //user_id条件的值
        Collection<Long> userIdValues = columnNameAndShardingValuesMap.get("user_id");
        //id条件的值
        Collection<Long> idValues = columnNameAndShardingValuesMap.get("id");
        //program_id条件的值（program_id 与 id 同值域，分片结果一致）
        Collection<Long> programIdValues = columnNameAndShardingValuesMap.get("program_id");
        //payment表的分片列 out_order_no 值即订单号字符串，与 order_number 等价
        Collection<Long> outOrderNoValues = columnNameAndShardingValuesMap.get("out_order_no");
        //mobile条件的值（用户手机映射表分片键）
        Collection<?> mobileValues = (Collection<?>) (Object) columnNameAndShardingValuesMap.get("mobile");
        //email条件的值（用户邮箱映射表分片键）
        Collection<?> emailValues = (Collection<?>) (Object) columnNameAndShardingValuesMap.get("email");

        //订单号与用户ID同时出现时（如订单详情双条件查询），两个分片键必须路由到同一分片表，
        //否则说明基因位定义已不一致，fail-fast 防止静默错误路由
        ComplexGeneRoute.checkGeneConsistency(orderNumberValues, userIdValues,
                value -> ShardingGeneUtils.tableIndex(shardingCount, value), "表");
        //IN (...) 查询必须把每个分片键都路由到各自的物理表：只取 findFirst 会静默丢掉 N-1 张表的数据
        Set<Long> tableIndexes = new LinkedHashSet<>();
        for (Long shardingValue : ComplexGeneRoute.resolveValues(orderNumberValues, userIdValues, idValues,
                programIdValues, outOrderNoValues, mobileValues, emailValues)) {
            //表索引 = 分片键的低N位（N = log2(表数量)）
            tableIndexes.add(ShardingGeneUtils.tableIndex(shardingCount, shardingValue));
        }
        //如果没有分片键查询，则把所有真实表返回
        if (tableIndexes.isEmpty()) {
            return allActualSplitTableNames;
        }
        List<String> actualTableNames = new ArrayList<>(tableIndexes.size());
        for (Long tableIndex : tableIndexes) {
            actualTableNames.add(logicTableName + "_" + tableIndex);
        }
        return actualTableNames;
    }
}
