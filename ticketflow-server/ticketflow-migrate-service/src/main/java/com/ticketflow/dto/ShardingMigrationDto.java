package com.ticketflow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 分片迁移DTO。分库分表扩容迁移请求参数，基于基因法方案1实现。
 */
@Data
@Schema(title = "ShardingMigrationDto", description = "分库分表扩容迁移请求")
public class ShardingMigrationDto {

    @Schema(name = "oldDatabaseCount", description = "旧库数量", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "旧库数量不能为空")
    @Min(value = 1, message = "旧库数量最小为1")
    private Integer oldDatabaseCount;

    @Schema(name = "oldTableCount", description = "旧表数量", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "旧表数量不能为空")
    @Min(value = 1, message = "旧表数量最小为1")
    private Integer oldTableCount;

    @Schema(name = "newDatabaseCount", description = "新库数量", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "新库数量不能为空")
    @Min(value = 1, message = "新库数量最小为1")
    private Integer newDatabaseCount;

    @Schema(name = "newTableCount", description = "新表数量", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "新表数量不能为空")
    @Min(value = 1, message = "新表数量最小为1")
    private Integer newTableCount;

    @Schema(name = "batchSize", description = "每批处理数量，默认1000")
    private Integer batchSize = 1000;

    @Schema(name = "dryRun", description = "是否只预演不实际迁移，默认false")
    private Boolean dryRun = false;
}
