package com.ticketflow.constant;

import java.util.concurrent.TimeUnit;

/**
 * 节目订单常量。定义订单超时取消相关的核心配置常量，是订单失效时长的唯一来源。
 * <p>
 * 说明：分库分表的路由参数不在此处定义。订单分片由 ShardingSphere 配置
 * （{@code shardingsphere-order-*.yaml} 中的 sharding-count / table-sharding-count）
 * 与 {@code com.ticketflow.shardingsphere.ShardingGeneUtils} 的位运算共同决定，
 * 分片数以配置文件为唯一事实来源，避免常量与配置漂移。
 */
public class ProgramOrderConstant {

    public static final String DELAY_ORDER_CANCEL_TOPIC = "d_delay_order_cancel_topic";

    /**
     * 订单未支付自动取消的时长。延迟队列按此值投递取消消息。
     */
    public static final Long DELAY_ORDER_CANCEL_TIME = 2L;

    public static final TimeUnit DELAY_ORDER_CANCEL_TIME_UNIT = TimeUnit.MINUTES;

    public static final String DELAY_OPERATE_PROGRAM_DATA_TOPIC = "d_delay_operate_program_data_topic";

    /**
     * 超时未支付订单兜底关单任务相对于 {@link #DELAY_ORDER_CANCEL_TIME} 的额外容差（分钟）。
     * <p>
     * 正常路径由延迟队列在 {@link #DELAY_ORDER_CANCEL_TIME} 到期时实时触发取消；
     * 兜底任务只处理延迟队列消息丢失/消费异常的极端情况，因此必须晚于正常路径，
     * 留出容差避免与延迟队列在同一时刻重复关单。
     */
    public static final int ORDER_CANCEL_FALLBACK_TOLERANCE_MINUTES = 1;
}
