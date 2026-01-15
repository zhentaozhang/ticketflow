package com.ticketflow.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ticketflow.dto.ShardingMigrationDto;
import com.ticketflow.entity.Order;
import com.ticketflow.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.infra.hint.HintManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分库分表扩容迁移服务（基因法方案1）
 * <p>
 * 核心设计：
 * - 使用 Hint 强制路由策略，直接指定物理库表
 * - 订单号固定嵌入 userId 后6位作为基因
 * - 扩容时按新算法重新计算每条数据的目标位置
 * - 只迁移位置发生变化的数据
 * <p>
 * 扩容路径示例：
 * - 2库4表 → 2库8表（只加表）
 *
 **/
@Slf4j
@Service
public class ShardingMigrationService {

    @Autowired
    private OrderMapper orderMapper;

    /**
     * 执行分库分表扩容迁移
     *
     * @param dto 迁移参数
     * @return 迁移结果统计
     */
    public MigrationStatistics migrate(ShardingMigrationDto dto) {
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("开始分库分表扩容迁移（基因法方案1 - Hint强制路由）");
        log.info("旧配置：{}库{}表", dto.getOldDatabaseCount(), dto.getOldTableCount());
        log.info("新配置：{}库{}表", dto.getNewDatabaseCount(), dto.getNewTableCount());
        log.info("预演模式：{}", dto.getDryRun());
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        MigrationStatistics statistics = new MigrationStatistics();

        // 遍历所有旧的物理表
        for (int dbIndex = 0; dbIndex < dto.getOldDatabaseCount(); dbIndex++) {
            for (int tableIndex = 0; tableIndex < dto.getOldTableCount(); tableIndex++) {
                String sourceDb = "ds_" + dbIndex;
                String sourceTable = "d_order_" + tableIndex;

                log.info("处理源表：{}.{}", sourceDb, sourceTable);

                // 迁移该表的数据
                TableMigrationResult result = migrateTableData(
                        dbIndex, tableIndex,
                        dto.getOldDatabaseCount(), dto.getOldTableCount(),
                        dto.getNewDatabaseCount(), dto.getNewTableCount(),
                        dto.getBatchSize(), dto.getDryRun()
                );

                statistics.totalScanned += result.scannedCount;
                statistics.totalMigrated += result.migratedCount;
                statistics.totalSkipped += result.skippedCount;

                log.info("表 {}.{} 处理完成：扫描{}条，迁移{}条，跳过{}条",
                        sourceDb, sourceTable,
                        result.scannedCount, result.migratedCount, result.skippedCount);
            }
        }

        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("✅ 扩容迁移完成！");
        log.info("总计扫描：{} 条", statistics.totalScanned);
        log.info("总计迁移：{} 条", statistics.totalMigrated);
        log.info("总计跳过：{} 条（位置未变化）", statistics.totalSkipped);
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        return statistics;
    }

    /**
     * 迁移单张表的数据
     */
    private TableMigrationResult migrateTableData(int sourceDbIndex, int sourceTableIndex,
                                                  int oldDbCount, int oldTableCount,
                                                  int newDbCount, int newTableCount,
                                                  int batchSize, boolean dryRun) {
        TableMigrationResult result = new TableMigrationResult();
        long lastId = 0;
        int batchCount = 0;

        while (true) {
            batchCount++;

            // ═══════════════════════════════════════════════════════
            // 第1步：使用 Hint 强制路由，从源表分批读取数据
            // ═══════════════════════════════════════════════════════
            List<Order> orders;
            try (HintManager hintManager = HintManager.getInstance()) {
                // Hint 强制路由到指定的物理库和物理表
                hintManager.addDatabaseShardingValue("d_order", String.valueOf(sourceDbIndex));
                hintManager.addTableShardingValue("d_order", String.valueOf(sourceTableIndex));

                orders = orderMapper.selectList(
                        Wrappers.lambdaQuery(Order.class)
                                .gt(Order::getId, lastId)
                                .orderByAsc(Order::getId)
                                .last("LIMIT " + batchSize)
                );
            }

            if (orders.isEmpty()) {
                break;
            }

            result.scannedCount += orders.size();

            // ═══════════════════════════════════════════════════════
            // 第2步：按新算法计算目标位置，分组待迁移数据
            // ═══════════════════════════════════════════════════════
            // key: "目标库索引_目标表索引", value: 待迁移的订单列表
            Map<String, List<Order>> targetGroupMap = new HashMap<>();

            for (Order order : orders) {
                // 使用订单号计算新位置（订单号低6位包含userId基因）
                Long shardingKey = order.getOrderNumber();

                // 计算新的表索引和库索引
                int newTableIndex = calculateTableIndex(shardingKey, newTableCount);
                int newDbIndex = calculateDatabaseIndex(shardingKey, newDbCount, newTableCount);

                // 判断位置是否变化
                if (newDbIndex == sourceDbIndex && newTableIndex == sourceTableIndex) {
                    // 位置未变，跳过
                    result.skippedCount++;
                } else {
                    // 位置变化，需要迁移
                    String targetKey = newDbIndex + "_" + newTableIndex;
                    targetGroupMap.computeIfAbsent(targetKey, k -> new ArrayList<>()).add(order);
                }
            }

            // ═══════════════════════════════════════════════════════
            // 第3步：批量迁移到目标表
            // ═══════════════════════════════════════════════════════
            if (!dryRun) {
                for (Map.Entry<String, List<Order>> entry : targetGroupMap.entrySet()) {
                    String[] parts = entry.getKey().split("_");
                    int targetDbIndex = Integer.parseInt(parts[0]);
                    int targetTableIndex = Integer.parseInt(parts[1]);
                    List<Order> toMigrate = entry.getValue();

                    // 插入到目标表
                    insertToTarget(targetDbIndex, targetTableIndex, toMigrate);

                    // 从源表删除
                    deleteFromSource(sourceDbIndex, sourceTableIndex, toMigrate);

                    result.migratedCount += toMigrate.size();
                }
            } else {
                // 预演模式，只统计不实际迁移
                for (List<Order> toMigrate : targetGroupMap.values()) {
                    result.migratedCount += toMigrate.size();
                }
            }

            // 更新游标
            lastId = orders.get(orders.size() - 1).getId();

            // 控制速度，避免数据库压力过大
            if (batchCount % 10 == 0) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        return result;
    }

    /**
     * 使用 Hint 强制路由，批量插入到目标表
     */
    @Transactional(rollbackFor = Exception.class)
    public void insertToTarget(int dbIndex, int tableIndex, List<Order> orders) {
        try (HintManager hintManager = HintManager.getInstance()) {
            hintManager.addDatabaseShardingValue("d_order", String.valueOf(dbIndex));
            hintManager.addTableShardingValue("d_order", String.valueOf(tableIndex));

            for (Order order : orders) {
                orderMapper.insert(order);
            }
        }
    }

    /**
     * 使用 Hint 强制路由，从源表删除已迁移数据
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteFromSource(int dbIndex, int tableIndex, List<Order> orders) {
        try (HintManager hintManager = HintManager.getInstance()) {
            hintManager.addDatabaseShardingValue("d_order", String.valueOf(dbIndex));
            hintManager.addTableShardingValue("d_order", String.valueOf(tableIndex));

            List<Long> ids = orders.stream().map(Order::getId).toList();
            orderMapper.physicalDeleteByIds(ids);
        }
    }

    /**
     * 计算表索引（取低N位）
     * N = log2(tableCount)
     * <p>
     * 示例：tableCount=8 时，取低3位
     */
    private int calculateTableIndex(Long shardingKey, int tableCount) {
        return (int) ((tableCount - 1) & shardingKey);
    }

    /**
     * 计算库索引（右移表基因位后取低M位）
     * M = log2(databaseCount)
     * <p>
     * 示例：tableCount=8, databaseCount=4 时，右移3位后取低2位
     */
    private int calculateDatabaseIndex(Long shardingKey, int databaseCount, int tableCount) {
        long tableGeneLength = log2N(tableCount);
        return (int) ((databaseCount - 1) & (shardingKey >> tableGeneLength));
    }

    private long log2N(long count) {
        return (long) (Math.log(count) / Math.log(2));
    }

    /**
     * 单表迁移结果
     */
    public static class TableMigrationResult {
        public int scannedCount = 0;
        public int migratedCount = 0;
        public int skippedCount = 0;
    }

    /**
     * 迁移统计结果
     */
    public static class MigrationStatistics {
        public int totalScanned = 0;
        public int totalMigrated = 0;
        public int totalSkipped = 0;
    }
}
