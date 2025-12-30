package com.ticketflow.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * 对账任务数据。封装需要对账的订单相关数据集合，用于Redis与DB的数据一致性校验。
 */
@Data
@Schema(title="ReconciliationTaskData", description ="需要进行添加的数据")
public class ReconciliationTaskData {
    
    @Schema(name ="programId", type ="Long", description ="节目id")
    private Long programId;
    
    @Schema(name ="addRedisRecordData", type ="Map", description ="需要向redis添加的数据")
    private Map<String, ProgramRecord> addRedisRecordData;
    
}
