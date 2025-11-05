package com.ticketflow.core;

/**
 * 延迟队列消费者接口——由业务方实现 execute(content) 方法。
 *
 * 每个 topic 对应一个 ConsumerTask 实现，在 DelayQueueInitHandler 启动时注册
 */
public interface ConsumerTask {
    
    /**
     * 消费任务
     * @param content 具体参数
     * */
    void execute(String content);
    /**
     * 主题
     * @return 主题
     * */
    String topic();
}
