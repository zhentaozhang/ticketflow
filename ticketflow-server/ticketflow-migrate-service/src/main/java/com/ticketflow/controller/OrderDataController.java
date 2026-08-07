package com.ticketflow.controller;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.ShardingMigrationDto;
import com.ticketflow.service.ShardingMigrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 订单数据迁移 API——分库分表扩容时的 Hint 路由迁移触发
 */
@RestController
@RequestMapping("/order/data")
@Tag(name = "order/data", description = "订单数据迁移")
public class OrderDataController {
    
    @Autowired
    private ShardingMigrationService shardingMigrationService;
    
    /**
     * 分库分表扩容迁移接口（基因法方案1）
     * 
     * 使用 Hint 强制路由策略，直接指定物理库表
     * 
     * 使用场景：从 2库4表 扩容到 2库8表
     * 
     * 注意事项：
     * 1. 执行前请确保新表已创建（d_order_4 ~ d_order_7）
     * 2. 建议先使用 dryRun=true 预演，确认迁移数量后再正式执行
     * 3. 迁移完成后需要修改 order-service 的 shardingsphere 配置文件
     */
    @Operation(summary = "分库分表扩容迁移（基因法方案1）")
    @PostMapping(value = "/sharding/migrate")
    public ApiResponse<Map<String, Object>> shardingMigrate(@RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun) {
        try {
            //从2库4表扩容到2库8表
            ShardingMigrationDto shardingMigrationDto = new ShardingMigrationDto();
            shardingMigrationDto.setOldDatabaseCount(2);
            shardingMigrationDto.setOldTableCount(4);
            shardingMigrationDto.setNewDatabaseCount(2);
            shardingMigrationDto.setNewTableCount(8);
            shardingMigrationDto.setBatchSize(1000);
            shardingMigrationDto.setDryRun(dryRun);
            ShardingMigrationService.MigrationStatistics statistics = shardingMigrationService.migrate(shardingMigrationDto);
            
            Map<String, Object> result = new HashMap<>(8);
            result.put("totalScanned", statistics.totalScanned);
            result.put("totalMigrated", statistics.totalMigrated);
            result.put("totalSkipped", statistics.totalSkipped);
            result.put("dryRun", shardingMigrationDto.getDryRun());
            result.put("message", shardingMigrationDto.getDryRun() ? "预演完成，未实际迁移" : "迁移完成");
            
            return ApiResponse.ok(result);
        } catch (Throwable e) {
            return ApiResponse.error("迁移失败：" + e.getMessage());
        }
    }
}