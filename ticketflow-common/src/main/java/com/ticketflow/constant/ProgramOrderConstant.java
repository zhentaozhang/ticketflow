package com.ticketflow.constant;

import java.util.concurrent.TimeUnit;

/**
 * 节目订单常量。定义延迟取消订单、分库分表基数等核心配置常量。
 */
public class ProgramOrderConstant {
    
    public static final String DELAY_ORDER_CANCEL_TOPIC ="d_delay_order_cancel_topic";
    
    public static final Long DELAY_ORDER_CANCEL_TIME = 2L;
    
    public static final TimeUnit DELAY_ORDER_CANCEL_TIME_UNIT = TimeUnit.MINUTES;
    
    public static final String DELAY_OPERATE_PROGRAM_DATA_TOPIC = "d_delay_operate_program_data_topic";
    
    /** 原始分库数量 */
    public static final int ORIGINAL_DATABASE_COUNT = 2;
    
    /** 原始分表数量 */
    public static final int ORIGINAL_TABLE_COUNT = 4;
    
    /**
     * 1024 = 2¹⁰。数据库从 2 库 × 4 表 = 8 物理表扩展到 64 库 × 64 表 = 4096 物理表时，
     * 虚拟分片无需改动（1024 固定）。扩缩容只需调整 ORIGINAL_DATABASE_COUNT / ORIGINAL_TABLE_COUNT
     * 并迁移少量虚拟分片即可。
     */
    public static final int VIRTUAL_SHARD_COUNT = 1024;
}
