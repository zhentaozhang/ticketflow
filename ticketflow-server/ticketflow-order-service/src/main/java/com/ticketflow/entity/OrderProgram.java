package com.ticketflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ticketflow.data.BaseTableData;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 订单节目关联。记录订单中包含的节目信息及对账处理状态。
 * 数据表: d_order_program
 */
@Data
@TableName("d_order_program")
public class OrderProgram extends BaseTableData implements Serializable {

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
     * 订单编号
     * */
    private Long orderNumber;

    /**
     * 记录id
     */
    private Long identifierId;

    /**
     * 处理状态 1:未处理 2:已处理
     */
    private Integer handleStatus;
}
