package com.ticketflow.initialize.constant;

/**
 * 初始化执行策略类型常量。
 * 定义了 3 种 Spring 初始化时机：
 * APPLICATION_EVENT_LISTENER  — ApplicationStartedEvent 事件驱动
 * APPLICATION_POST_CONSTRUCT  — @PostConstruct 立即执行
 * APPLICATION_INITIALIZING_BEAN — InitializingBean afterPropertiesSet
 * <p>
 * 由 CompositeInit 根据策略类型分类调度
 */
public class InitializeHandlerType {

    public static final String APPLICATION_EVENT_LISTENER = "application_event_listener";

    public static final String APPLICATION_POST_CONSTRUCT = "application_post_construct";

    public static final String APPLICATION_INITIALIZING_BEAN = "application_initializing_bean";

}
