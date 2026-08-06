package com.ticketflow.shardingsphere;

import cn.hutool.core.collection.CollectionUtil;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingAlgorithm;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * ShardingSphere 自定义复合分片：订单表分库算法（基因法）。
 * 从 orderNumber（long）中提取 user_id 基因位，决定数据落入哪个物理库，
 * 保证同一用户的订单集中在同一库中
 */
public class DatabaseOrderComplexGeneArithmetic implements ComplexKeysShardingAlgorithm<Long> {
    /**
     * 属性分库名
     *
     */
    private static final String SHARDING_COUNT_KEY_NAME = "sharding-count";

    /**
     * 属性分表名
     *
     */
    private static final String TABLE_SHARDING_COUNT_KEY_NAME = "table-sharding-count";

    /**
     * 分库数量
     *
     */
    private int shardingCount;

    /**
     * 分表数量
     *
     */
    private int tableShardingCount;

    @Override
    public void init(Properties props) {
        this.shardingCount = Integer.parseInt(props.getProperty(SHARDING_COUNT_KEY_NAME));
        this.tableShardingCount = Integer.parseInt(props.getProperty(TABLE_SHARDING_COUNT_KEY_NAME));
        // 位运算分片要求分片数为 2 的幂，配置错误早暴露，避免静默错误路由
        ShardingGeneUtils.checkPowerOfTwo(shardingCount, SHARDING_COUNT_KEY_NAME);
        ShardingGeneUtils.checkPowerOfTwo(tableShardingCount, TABLE_SHARDING_COUNT_KEY_NAME);
    }


    @Override
    public Collection<String> doSharding(Collection<String> allActualSplitDatabaseNames,
                                         ComplexKeysShardingValue<Long> complexKeysShardingValue) {
        //返回的真实库名集合
        List<String> actualDatabaseNames = new ArrayList<>(allActualSplitDatabaseNames.size());
        //查询中的列名和值
        Map<String, Collection<Long>> columnNameAndShardingValuesMap =
                complexKeysShardingValue.getColumnNameAndShardingValuesMap();
        //如果没有条件查询，那么就查所有的分表
        if (CollectionUtil.isEmpty(columnNameAndShardingValuesMap)) {
            return allActualSplitDatabaseNames;
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

        Long value = null;
        //如果是order_number查询
        if (CollectionUtil.isNotEmpty(orderNumberValues)) {
            value = orderNumberValues.stream().findFirst()
                    .orElseThrow(() -> new TicketFlowFrameException(BaseCode.ORDER_NUMBER_NOT_EXIST));
            //如果是user_id查询
        } else if (CollectionUtil.isNotEmpty(userIdValues)) {
            value = userIdValues.stream().findFirst()
                    .orElseThrow(() -> new TicketFlowFrameException(BaseCode.USER_ID_NOT_EXIST));
            //如果是id查询
        } else if (CollectionUtil.isNotEmpty(idValues)) {
            value = idValues.stream().findFirst().orElse(null);
            //如果是program_id查询
        } else if (CollectionUtil.isNotEmpty(programIdValues)) {
            value = programIdValues.stream().findFirst().orElse(null);
            //如果是out_order_no查询（订单号字符串，与 order_number 同基因）
        } else if (CollectionUtil.isNotEmpty(outOrderNoValues)) {
            value = Long.parseLong(String.valueOf(outOrderNoValues.stream().findFirst().orElse(null)));
        }
        //订单号与用户ID同时出现时（如订单列表双条件查询），两个分片键必须路由到同一分片，
        //否则说明基因位定义已不一致，fail-fast 防止静默错误路由
        if (CollectionUtil.isNotEmpty(orderNumberValues) && CollectionUtil.isNotEmpty(userIdValues)) {
            long orderNumberIndex = ShardingGeneUtils.databaseIndex(shardingCount,
                    orderNumberValues.stream().findFirst().get(), tableShardingCount);
            long userIdIndex = ShardingGeneUtils.databaseIndex(shardingCount,
                    userIdValues.stream().findFirst().get(), tableShardingCount);
            if (orderNumberIndex != userIdIndex) {
                throw new IllegalArgumentException(
                        String.format("order_number 与 user_id 基因位路由不一致，order_number 路由到库 %d，user_id 路由到库 %d",
                                orderNumberIndex, userIdIndex));
            }
        }
        //如果order_number或者user_id的值存在
        if (Objects.nonNull(value)) {
            //获得值后再获得实际的分库的索引
            long databaseIndex = calculateDatabaseIndex(shardingCount, value, tableShardingCount);
            String databaseIndexStr = String.valueOf(databaseIndex);
            for (String actualSplitDatabaseName : allActualSplitDatabaseNames) {
                //将所有的分库名和得到的分库索引进行精确匹配（contains 在库名为 ds_1/ds_10 时存在前缀误匹配）
                if (actualSplitDatabaseName.equals("ds_" + databaseIndexStr)) {
                    actualDatabaseNames.add(actualSplitDatabaseName);
                    //记录命中分布，供 ShardingMetrics 快照观察分片热点
                    ShardingMetrics.recordDatabaseHit(databaseIndex);
                    break;
                }
            }
            return actualDatabaseNames;
        } else {
            //如果没有分片键查询，则把所有真实库返回
            return allActualSplitDatabaseNames;
        }
    }

    /**
     * 计算分库索引
     * <p>
     * 核心思路：
     * - 分表使用ID的低位bit（最后 log2(tableCount) 位）
     * - 分库使用ID的中高位bit（跳过表基因位后的 log2(databaseCount) 位）
     * - 直接使用位运算，避免hashCode导致的分布不均
     * <p>
     * 基因位分布示例（userId后6位 = [bit5][bit4][bit3][bit2][bit1][bit0]）：
     * - 2库4表：表基因=bit0-1，库基因=bit2
     * - 4库8表：表基因=bit0-2，库基因=bit3-4
     * - 8库8表：表基因=bit0-2，库基因=bit3-5（用满6位上限）
     *
     * @param databaseCount 数据库总数
     * @param splicingKey   分片键（订单号或userId，低6位包含相同基因）
     * @param tableCount    表总数
     * @return 分配到的数据库编号
     */
    public long calculateDatabaseIndex(Integer databaseCount, Long splicingKey, Integer tableCount) {
        return ShardingGeneUtils.databaseIndex(databaseCount, splicingKey, tableCount);
    }
}
