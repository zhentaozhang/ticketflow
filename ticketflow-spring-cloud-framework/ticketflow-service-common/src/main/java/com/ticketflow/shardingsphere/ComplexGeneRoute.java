package com.ticketflow.shardingsphere;

import cn.hutool.core.collection.CollectionUtil;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.LongUnaryOperator;

/**
 * 复合分片键的统一解析：把 `IN (...)` 里的每个值都解析成基因值，供分库/分表算法做 union 路由。
 * <p>
 * 历史实现用 {@code stream().findFirst()} 只取第一个分片键，导致 `order_number IN (...)`、
 * `program_id IN (...)` 这类跨基因的批量查询只路由到一张表/一个库，其余分片的数据被静默丢弃
 * （后台订单列表只看到 1/8，对账补偿只看到 1/16）。
 * <p>
 * 分库算法与分表算法共用本类，避免两处各写一份解析逻辑产生漂移。
 */
final class ComplexGeneRoute {

    private ComplexGeneRoute() {
    }

    /**
     * 解析所有出现过的分片键取值（去重，保持出现顺序）。
     * <p>
     * 顺序即优先级：order_number → user_id → id → program_id → out_order_no → mobile → email。
     * 多个分片键同时存在时取并集：这是 WHERE 条件的超集，ShardingSphere 仍会用原条件过滤行，
     * 只是多扫了几张物理表，不会返回错误数据。
     *
     * @param orderNumberValues order_number 条件的值（订单号，与 user_id 低 6 位同基因）
     * @param userIdValues      user_id 条件的值
     * @param idValues          id 条件的值
     * @param programIdValues   program_id 条件的值（与 id 同值域）
     * @param outOrderNoValues  out_order_no 条件的值（支付表分片列，订单号字符串）
     * @param mobileValues      mobile 条件的值（手机号映射表分片键，哈希取模）
     * @param emailValues       email 条件的值（邮箱映射表分片键，哈希取模）
     * @return 基因值集合，空集合表示没有可用分片键
     */
    static Set<Long> resolveValues(Collection<Long> orderNumberValues,
                                   Collection<Long> userIdValues,
                                   Collection<Long> idValues,
                                   Collection<Long> programIdValues,
                                   Collection<Long> outOrderNoValues,
                                   Collection<?> mobileValues,
                                   Collection<?> emailValues) {
        Set<Long> values = new LinkedHashSet<>();
        if (CollectionUtil.isNotEmpty(orderNumberValues)) {
            values.addAll(orderNumberValues);
        }
        if (CollectionUtil.isNotEmpty(userIdValues)) {
            values.addAll(userIdValues);
        }
        if (CollectionUtil.isNotEmpty(idValues)) {
            values.addAll(idValues);
        }
        if (CollectionUtil.isNotEmpty(programIdValues)) {
            values.addAll(programIdValues);
        }
        if (CollectionUtil.isNotEmpty(outOrderNoValues)) {
            // out_order_no 实际是字符串，被上层以 Collection<Long> 透传，必须按 Object 读取
            for (Object value : (Collection<?>) outOrderNoValues) {
                values.add(Long.parseLong(String.valueOf(value)));
            }
        }
        if (CollectionUtil.isNotEmpty(mobileValues)) {
            for (Object value : mobileValues) {
                values.add(hashOf(value));
            }
        }
        if (CollectionUtil.isNotEmpty(emailValues)) {
            for (Object value : emailValues) {
                values.add(hashOf(value));
            }
        }
        return values;
    }

    /**
     * order_number 与 user_id 同时出现时（如订单详情双条件查询），两个分片键必须路由到同一分片，
     * 否则说明基因位定义已不一致，fail-fast 防止静默错误路由。
     * <p>
     * 只在两个条件各只有一个值时校验：批量 IN 场景取并集是安全超集，不做逐值配对校验。
     *
     * @param orderNumberValues order_number 条件的值
     * @param userIdValues      user_id 条件的值
     * @param indexFunction     索引计算函数（分表用 tableIndex，分库用 databaseIndex）
     * @param shardName         分片维度名称（"表" 或 "库"），用于错误信息
     */
    static void checkGeneConsistency(Collection<Long> orderNumberValues,
                                     Collection<Long> userIdValues,
                                     LongUnaryOperator indexFunction,
                                     String shardName) {
        if (CollectionUtil.isEmpty(orderNumberValues) || CollectionUtil.isEmpty(userIdValues)) {
            return;
        }
        if (orderNumberValues.size() > 1 || userIdValues.size() > 1) {
            return;
        }
        long orderNumberIndex = indexFunction.applyAsLong(orderNumberValues.iterator().next());
        long userIdIndex = indexFunction.applyAsLong(userIdValues.iterator().next());
        if (orderNumberIndex != userIdIndex) {
            throw new IllegalArgumentException(
                    String.format("order_number 与 user_id 基因位路由不一致，order_number 路由到%s %d，user_id 路由到%s %d",
                            shardName, orderNumberIndex, shardName, userIdIndex));
        }
    }

    private static long hashOf(Object value) {
        return String.valueOf(value).hashCode() & 0x7FFFFFFFL;
    }
}
