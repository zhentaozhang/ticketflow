package com.ticketflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ticketflow.data.BaseTableData;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 节目对账记录任务。记录节目数据的对账/同步任务状态，
 * 用于确保订单系统与节目系统之间的数据一致性。
 * 数据表: d_program_record_task
 */
@Data
@TableName("d_program_record_task")
public class ProgramRecordTask extends BaseTableData implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    private Long id;
    
    /**
     * 节目表id
     */
    private Long programId;

    /**
     * 处理状态 1:未处理 1:已处理
     */
    private Integer handleStatus;
    
    
}
