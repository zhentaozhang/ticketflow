package com.ticketflow.shardingsphere;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * 基因法分片路由的运行时统计（进程内静态计数，无需外部组件）。
 * 用于观察各分库的真实路由命中分布，暴露低流量时段的分片热点。
 */
public final class ShardingMetrics {

    /**
     * 各库（库索引 → 命中次数）
     */
    private static final ConcurrentHashMap<Long, LongAdder> DATABASE_HITS = new ConcurrentHashMap<>();

    /**
     * 总路由次数
     */
    private static final LongAdder TOTAL = new LongAdder();

    private ShardingMetrics() {
    }

    /**
     * 记录一次路由命中，用于追踪
     *
     * @param databaseIndex 分库索引
     */
    public static void recordDatabaseHit(long databaseIndex) {
        TOTAL.increment();
        DATABASE_HITS.computeIfAbsent(databaseIndex, key -> new LongAdder()).increment();
    }

    /**
     * 生成可读的统计快照，包含总次数与各库命中占比
     *
     * @return 统计信息
     */
    public static String snapshot() {
        long total = TOTAL.sum();
        if (total == 0) {
            return "ShardingMetrics: no routing recorded yet";
        }
        String distribution = DATABASE_HITS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    long count = entry.getValue().sum();
                    return String.format("ds_%d=%d(%.2f%%)", entry.getKey(), count, count * 100.0 / total);
                })
                .collect(Collectors.joining(", "));
        return String.format("ShardingMetrics: total=%d, hits=[%s]", total, distribution);
    }

    /**
     * 重置统计，用于测试或滚动窗口
     */
    public static void reset() {
        DATABASE_HITS.clear();
        TOTAL.reset();
    }
}