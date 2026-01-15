package com.ticketflow.shardingsphere.algorithm;

import org.apache.shardingsphere.sharding.api.sharding.hint.HintShardingAlgorithm;
import org.apache.shardingsphere.sharding.api.sharding.hint.HintShardingValue;

import java.util.Collection;
import java.util.Collections;

/**
 * 订单表强制路由算法
 * <p>
 * 用于数据迁移时强制指定物理表
 * 配合 HintManager.addTableShardingValue() 使用
 */
public class TableOrderHintShardingAlgorithm implements HintShardingAlgorithm<String> {

    @Override
    public Collection<String> doSharding(final Collection<String> availableTargetNames,
                                         final HintShardingValue<String> shardingValue) {
        // 从 HintManager 中获取强制路由的值
        // 例如：hintManager.addTableShardingValue("d_order", "0")
        // shardingValue.getValues() 会返回 ["0"]
        Collection<String> values = shardingValue.getValues();

        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("强制路由值不能为空");
        }

        // 获取第一个值（表后缀，如 "0", "1", "2", "3"）
        String tableSuffix = values.iterator().next();

        // 根据后缀构造物理表名称（逻辑表为 d_order，物理表为 d_order_0, d_order_1, ...）
        String targetTable = "d_order_" + tableSuffix;

        // 验证目标表是否存在
        if (!availableTargetNames.contains(targetTable)) {
            throw new IllegalArgumentException(
                    String.format("目标表 %s 不存在，可用表: %s",
                            targetTable, availableTargetNames)
            );
        }

        // 返回目标表（单个表）
        return Collections.singletonList(targetTable);
    }
}
