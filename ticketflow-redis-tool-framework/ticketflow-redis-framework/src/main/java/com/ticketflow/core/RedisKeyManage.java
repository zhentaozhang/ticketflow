package com.ticketflow.core;


import lombok.Getter;

/**
 * Redis key 集中注册表（枚举模式）。
 * 所有业务模块通过此枚举引用 key 模板，配合 RedisKeyBuild 注入环境前缀，
 * 实现多环境 key 隔离。
 * <p>
 * 命名规范：
 * - 通用数据：d_mai_ 前缀
 * - 节目服务：d_mai_program_ 前缀
 * - 订单服务：d_mai_order_ 前缀
 * - 规则数据：单一单词无前缀（rule_ / depth_rule_ / z_set_rule_stat_）
 * <p>
 * 使用方式：RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM, programId)
 * 最终格式: {环境前缀}:d_mai_program_{programId}
 */
@Getter
public enum RedisKeyManage {
    /**
     * redis 缓存 key管理
     *
     */

    USER_LOGIN("user_login_%s_%s", "user_login", "value为UserVo类型", "k"),

    PRODUCT_STOCK("product_stock:%s", "商品库存id", "value为库存", "k"),

    //分布式datacenter_id
    DISTRIBUTED_DATACENTER_ID("distributed_datacenter_id:%s", "分布式datacenter_id", "分布式datacenter_id的值", "lk"),

    ALL_RULE_HASH("all_rule_hash", "所有规则的key", "所有规则的Hash", "k"),
    RULE("rule", "调用限制规则的key", "调用限制规则的value", "k"),

    RULE_LIMIT("rule_limit_%s", "调用限制时间的key", "调用限制时间的value", "k"),

    Z_SET_RULE_STAT("z_set_rule_stat_%s", "规则zset", "value为zset类型", "k"),

    DEPTH_RULE("depth_rule", "深度调用限制规则的key", "深度调用限制规则的value", "k"),

    DEPTH_RULE_LIMIT("depth_rule_limit_%s_%s", "深度调用限制时间的key", "深度调用限制时间的value", "k"),

    API_STAT_CONTROLLER_METHOD_DATA("api_stat_controller_method_data:%s", "controller的key", "controller的value", "k"),

    API_STAT_SERVICE_METHOD_DATA("api_stat_service_method_data:%s", "service的key", "service的value", "k"),

    API_STAT_DAO_METHOD_DATA("api_stat_dao_method_data:%s", "dao的key", "dao的value", "k"),

    API_STAT_METHOD_HIERARCHY("api_stat_method_Hierarchy:%s", "method_Hierarchy的key", "method_Hierarchy的value", "k"),

    API_STAT_METHOD_DETAIL("api_stat_method_detail:%s", "api_stat_method_detail的key", "api_stat_method_detail的value", "k"),

    API_STAT_CONTROLLER_SORTED_SET("api_stat_controller_sorted_set", "api_stat_controller_sorted_set的key", "api_stat_controller_sorted_set的value", "k"),

    API_STAT_CONTROLLER_CHILDREN_SET("api_stat_controller_children_set:%s", "api_stat_controller_children_set的key", "api_stat_controller_children_set的value", "k"),

    API_STAT_SERVICE_CHILDREN_SET("api_stat_service_children_set:%s", "api_stat_service_children_set的key", "api_stat_service_children_set的value", "k"),

    PLATFORM_NOTICE_FLAG("platform_notice_flag", "platform_notice_flag的key", "platform_notice_flag的value", "k"),

    CHANNEL_DATA("channel_data_%s", "channel_data的key", "channel_data的value", "k"),

    PROGRAM("d_mai_program_%s", "节目id", "节目", "k"),

    PROGRAM_GROUP("d_mai_program_group_%s", "节目分组id", "节目分组", "k"),

    PROGRAM_SHOW_TIME("d_mai_program_show_time_%s", "节目演出时间id", "节目演出时间", "k"),

    PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH("d_mai_program_seat_no_sold_resolution_hash_%s_%s", "节目座位未售卖集合_节目id_节目类型id", "节目座位未售卖集合", "k"),

    PROGRAM_SEAT_LOCK_RESOLUTION_HASH("d_mai_program_seat_lock_resolution_hash_%s_%s", "节目座位锁定集合_节目id_节目类型id", "节目座位锁定集合", "k"),

    PROGRAM_SEAT_SOLD_RESOLUTION_HASH("d_mai_program_seat_sold_resolution_hash_%s_%s", "节目座位已售卖集合_节目id_节目类型id", "节目座位已售卖集合", "k"),

    PROGRAM_TICKET_CATEGORY_LIST("d_mai_program_ticket_category_list_%s", "节目票档集合id", "节目票档集合", "k"),

    PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION("d_mai_program_ticket_remain_number_hash_resolution_%s_%s", "节目余票数量_节目id_节目票档id", "节目余票数量", "k"),

    PROGRAM_CATEGORY_HASH("d_mai_program_category_hash", "节目类型hash集合", "节目类型hash集合", "k"),

    PROGRAM_RECORD("d_mai_program_record_%s", "节目记录id", "节目记录数据", "k"),

    PROGRAM_RECORD_FINISH("d_mai_program_record_finish_%s", "节目记录id", "节目记录数据", "k"),

    COUNTER_COUNT("d_mai_counter_count", "计数器的值的key", "计数器的值", "k"),

    COUNTER_TIMESTAMP("d_mai_counter_timestamp", "计数器的时间戳的key", "计数器的时间戳", "k"),

    VERIFY_CAPTCHA_ID("d_mai_verify_captcha_id_%s", "校验验证码id的key", "校验验证码id", "k"),

    TICKET_USER_LIST("d_mai_ticket_user_list_%s", "购票人列表的key", "购票人列表", "k"),

    ACCOUNT_ORDER_COUNT("d_mai_account_order_count_%s_%s", "账户下订单数量的key", "账户下订单数量", "k"),

    ACCOUNT_ORDER_COUNT_ALL("d_mai_account_order_count_*", "账户下订单数量的key", "账户下订单数量", "k"),

    ORDER_MQ("d_mai_order_mq_%s", "使用mq创建的订单的订单编号", "使用mq创建的订单的订单编号", "k"),

    DISCARD_ORDER("d_mai_discard_order_%s", "使用mq创建方式被丢弃的订单", "使用mq创建方式被丢弃的订单", "k"),

    ORDER_CREATE_PENDING("d_mai_order_create_pending_%s", "请求侧发送超时待确认订单_节目id", "发送超时待确认订单", "k"),

    ORDER_CREATE_PENDING_ALL("d_mai_order_create_pending_*", "请求侧发送超时待确认订单_通配", "发送超时待确认订单", "k"),

    V5_ORDER_CREATE_IDEMPOTENT("d_mai_program_order_create_idempotent_%s_%s", "V5下单幂等标记_用户id_节目id", "V5下单幂等标记", "k"),

    V5_ORDER_DB_PROJECTED("d_mai_order_db_projected_%s", "V5订单DB投影完成标记_订单号", "V5订单DB投影完成标记", "k"),

    LOGIN_USER_MOBILE_ERROR("d_mai_login_user_mobile_error_%s", "登录错误的用户手机号key", "登录错误的用户手机号次数", "k"),

    LOGIN_USER_EMAIL_ERROR("d_mai_login_user_email_error_%s", "登录错误的用户邮箱key", "登录错误的用户邮箱次数", "k"),

    AREA_PROVINCE_LIST("d_mai_area_province_list", "省地区集合", "省地区集合数据", "k");

    /**
     * key值
     *
     */
    private final String key;

    /**
     * key的说明
     *
     */
    private final String keyIntroduce;

    /**
     * value的说明
     *
     */
    private final String valueIntroduce;

    /**
     * 作者
     *
     */
    private final String author;

    RedisKeyManage(String key, String keyIntroduce, String valueIntroduce, String author) {
        this.key = key;
        this.keyIntroduce = keyIntroduce;
        this.valueIntroduce = valueIntroduce;
        this.author = author;
    }

}
