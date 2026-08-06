package com.ticketflow.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ticketflow.dto.ShardingMigrationDto;
import com.ticketflow.entity.Order;
import com.ticketflow.entity.OrderTicketUser;
import com.ticketflow.entity.OrderTicketUserRecord;
import com.ticketflow.mapper.MigrateMapper;
import com.ticketflow.mapper.OrderMapper;
import com.ticketflow.mapper.OrderTicketUserMapper;
import com.ticketflow.mapper.OrderTicketUserRecordMapper;
import com.ticketflow.shardingsphere.ShardingGeneUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.infra.hint.HintManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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
 * <p>
 * 支持的表：d_order / d_order_ticket_user / d_order_ticket_user_record（均按 order_number 基因分片），
 * 每次迁移会依次处理三张表。
 **/
@Slf4j
@Service
public class ShardingMigrationService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderTicketUserMapper orderTicketUserMapper;

    @Autowired
    private OrderTicketUserRecordMapper orderTicketUserRecordMapper;

    /**
     * 单张分片表的迁移上下文
     *
     * @param <T> 实体类型
     */
    private static class TableContext<T> {
        private final String logicTableName;
        private final MigrateMapper<T> mapper;
        private final Function<T, Long> idGetter;
        private final Function<T, Long> orderNumberGetter;

        TableContext(String logicTableName, MigrateMapper<T> mapper,
                     Function<T, Long> idGetter, Function<T, Long> orderNumberGetter) {
            this.logicTableName = logicTableName;
            this.mapper = mapper;
            this.idGetter = idGetter;
            this.orderNumberGetter = orderNumberGetter;
        }
    }

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

        // 三张订单主表均以 order_number 分片，依次处理
        List<TableContext<?>> tables = List.of(
                new TableContext<>("d_order", orderMapper,
                        Order::getId, Order::getOrderNumber),
                new TableContext<>("d_order_ticket_user", orderTicketUserMapper,
                        OrderTicketUser::getId, OrderTicketUser::getOrderNumber),
                new TableContext<>("d_order_ticket_user_record", orderTicketUserRecordMapper,
                        OrderTicketUserRecord::getId, OrderTicketUserRecord::getOrderNumber)
        );

        for (TableContext<?> table : tables) {
            migrateTable(table, dto, statistics);
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
    private void migrateTable(TableContext<?> table, ShardingMigrationDto dto, MigrationStatistics statistics) {
        // 遍历所有旧的物理表
        for (int dbIndex = 0; dbIndex < dto.getOldDatabaseCount(); dbIndex++) {
            for (int tableIndex = 0; tableIndex < dto.getOldTableCount(); tableIndex++) {
                String sourceDb = "ds_" + dbIndex;
                String sourceTable = table.logicTableName + "_" + tableIndex;

                log.info("处理源表：{}.{}", sourceDb, sourceTable);

                // 迁移该表的数据
                TableMigrationResult result = migrateTableData(
                        table, dbIndex, tableIndex,
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
    }

    /**
     * 迁移单张物理表的数据
     */
    private <T> TableMigrationResult migrateTableData(TableContext<T> table,
                                                      int sourceDbIndex, int sourceTableIndex,
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
            List<T> rows;
            try (HintManager hintManager = HintManager.getInstance()) {
                // Hint 强制路由到指定的物理库和物理表
                hintManager.addDatabaseShardingValue(table.logicTableName, String.valueOf(sourceDbIndex));
                hintManager.addTableShardingValue(table.logicTableName, String.valueOf(sourceTableIndex));

                QueryWrapper<T> wrapper = Wrappers.query();
                wrapper.orderByAsc("id");
                if (lastId > 0) {
                    wrapper.gt("id", lastId);
                }
                wrapper.last("LIMIT " + batchSize);
                rows = table.mapper.selectList(wrapper);
            }

            if (rows.isEmpty()) {
                break;
            }

            result.scannedCount += rows.size();

            // ═══════════════════════════════════════════════════════
            // 第2步：按新算法计算目标位置，分组待迁移数据
            // ═══════════════════════════════════════════════════════
            // key: "目标库索引_目标表索引", value: 待迁移的数据列表
            Map<String, List<T>> targetGroupMap = new HashMap<>();

            for (T row : rows) {
                // 使用订单号计算新位置（订单号低6位包含userId基因）
                Long shardingKey = table.orderNumberGetter.apply(row);

                // 计算新的表索引和库索引
                int newTableIndex = (int) ShardingGeneUtils.tableIndex(newTableCount, shardingKey);
                int newDbIndex = (int) ShardingGeneUtils.databaseIndex(newDbCount, shardingKey, newTableCount);

                // 判断位置是否变化
                if (newDbIndex == sourceDbIndex && newTableIndex == sourceTableIndex) {
                    // 位置未变，跳过
                    result.skippedCount++;
                } else {
                    // 位置变化，需要迁移
                    String targetKey = newDbIndex + "_" + newTableIndex;
                    targetGroupMap.computeIfAbsent(targetKey, k -> new ArrayList<>()).add(row);
                }
            }

            // ═══════════════════════════════════════════════════════
            // 第3步：批量迁移到目标表
            // ═══════════════════════════════════════════════════════
            if (!dryRun) {
                for (Map.Entry<String, List<T>> entry : targetGroupMap.entrySet()) {
                    String[] parts = entry.getKey().split("_");
                    int targetDbIndex = Integer.parseInt(parts[0]);
                    int targetTableIndex = Integer.parseInt(parts[1]);
                    List<T> toMigrate = entry.getValue();

                    // 插入到目标表
                    insertToTarget(table, targetDbIndex, targetTableIndex, toMigrate);

                    // 从源表删除
                    deleteFromSource(table, sourceDbIndex, sourceTableIndex, toMigrate);

                    result.migratedCount += toMigrate.size();
                }
            } else {
                // 预演模式，只统计不实际迁移
                for (List<T> toMigrate : targetGroupMap.values()) {
                    result.migratedCount += toMigrate.size();
                }
            }

            // 更新游标
            lastId = table.idGetter.apply(rows.get(rows.size() - 1));

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
     * 使用 Hint 强制路由，批量插入到目标表。
     * 使用 INSERT IGNORE 保证幂等：已存在的行跳过，迁移中断后重跑可自愈
     */
    @Transactional(rollbackFor = Exception.class)
    public <T> void insertToTarget(TableContext<T> table, int dbIndex, int tableIndex, List<T> rows) {
        try (HintManager hintManager = HintManager.getInstance()) {
            hintManager.addDatabaseShardingValue(table.logicTableName, String.valueOf(dbIndex));
            hintManager.addTableShardingValue(table.logicTableName, String.valueOf(tableIndex));

            table.mapper.batchInsertIgnore(rows);
        }
    }

    /**
     * 使用 Hint 强制路由，从源表删除已迁移数据
     */
    @Transactional(rollbackFor = Exception.class)
    public <T> void deleteFromSource(TableContext<T> table, int dbIndex, int tableIndex, List<T> rows) {
        try (HintManager hintManager = HintManager.getInstance()) {
            hintManager.addDatabaseShardingValue(table.logicTableName, String.valueOf(dbIndex));
            hintManager.addTableShardingValue(table.logicTableName, String.valueOf(tableIndex));

            List<Long> ids = rows.stream().map(table.idGetter).toList();
            table.mapper.physicalDeleteByIds(ids);
        }
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
