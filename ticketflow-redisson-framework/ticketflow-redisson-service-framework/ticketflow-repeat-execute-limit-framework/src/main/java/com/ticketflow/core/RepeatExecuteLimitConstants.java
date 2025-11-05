package com.ticketflow.core;

/**
 * 防重复执行业务名常量——集中管理 @RepeatExecuteLimit 中使用的 name 值。
 *
 * 每个常量对应一个业务场景（如创建订单、取消费单、支付等），
 * 避免 name 字符串在代码中分散，确保幂等 key 名一致
 */
public class RepeatExecuteLimitConstants {
    
    public static final String CONSUMER_API_DATA_MESSAGE = "consumer_api_data_message";
    
    public static final String CREATE_PROGRAM_ORDER = "create_program_order";
   
    public final static String CANCEL_PROGRAM_ORDER = "cancel_program_order";
    
    public static final String CREATE_PROGRAM_ORDER_MQ = "create_program_order_mq";
    
    public static final String PROGRAM_CACHE_REVERSE_MQ = "program_cache_reverse_mq";
    
    public final static String PAY_OR_CANCEL_PROGRAM_ORDER = "pay_or_cancel_program_order";
    
    public final static String REDUCE_REMAIN_NUMBER = "reduce_remain_number";
}
