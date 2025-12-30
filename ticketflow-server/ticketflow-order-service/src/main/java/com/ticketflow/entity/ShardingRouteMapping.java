package com.ticketflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ticketflow.data.BaseTableData;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 分片路由映射。数据库分片的路由配置，将逻辑分片ID(0-1023)
 * 映射到物理数据库和物理表，实现订单数据的水平拆分。
 * 数据表: d_sharding_route_mapping
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("d_sharding_route_mapping")
public class ShardingRouteMapping extends BaseTableData implements Serializable {
    
    private Long id;
    
    /**
     * 逻辑分片ID（0-1023）
     */
    private Integer logicalShardId;
    
    /**
     * 物理数据库名后缀（0-1，适用于所有库类型）
     */
    private String physicalDatabaseSuffix;
    
    /**
     * 物理表后缀（0-7，适用于所有表类型）
     * 适用于：d_order_{suffix}、d_order_ticket_user_{suffix}、d_order_ticket_user_record_{suffix}
     */
    private Integer physicalTableSuffix;
    
    /**
     * 版本号（用于热更新）
     */
    private Integer version;
}
