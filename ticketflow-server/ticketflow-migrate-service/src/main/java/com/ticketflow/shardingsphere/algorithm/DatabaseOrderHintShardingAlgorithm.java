package com.ticketflow.shardingsphere.algorithm;

import org.apache.shardingsphere.sharding.api.sharding.hint.HintShardingAlgorithm;
import org.apache.shardingsphere.sharding.api.sharding.hint.HintShardingValue;

import java.util.Collection;
import java.util.Collections;

/**
 * 订单库强制路由算法
 * <p>
 * 用于数据迁移时强制指定数据源（数据库）
 * 配合 HintManager.addDatabaseShardingValue() 使用
 */
public class DatabaseOrderHintShardingAlgorithm implements HintShardingAlgorithm<String> {

    @Override
    public Collection<String> doSharding(final Collection<String> availableTargetNames,
                                         final HintShardingValue<String> shardingValue) {
        // 从 HintManager 中获取强制路由的值
        // 例如：hintManager.addDatabaseShardingValue("d_order", "0") 
        // shardingValue.getValues() 会返回 ["0"]
        Collection<String> values = shardingValue.getValues();

        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("强制路由值不能为空");
        }

        // 获取第一个值（数据库后缀，如 "0" 或 "1"）
        String dbSuffix = values.iterator().next();

        // 根据后缀构造数据源名称（配置文件中的数据源名称为 ds_0, ds_1）
        String targetDataSource = "ds_" + dbSuffix;

        // 验证目标数据源是否存在
        if (!availableTargetNames.contains(targetDataSource)) {
            throw new IllegalArgumentException(
                    String.format("目标数据源 %s 不存在，可用数据源: %s",
                            targetDataSource, availableTargetNames)
            );
        }

        // 返回目标数据源（单个数据源）
        return Collections.singletonList(targetDataSource);
    }
}
