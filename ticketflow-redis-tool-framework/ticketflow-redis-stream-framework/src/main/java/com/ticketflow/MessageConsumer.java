package com.ticketflow;

import org.springframework.data.redis.connection.stream.ObjectRecord;

/**
 * Redis Stream消息处理接口。函数式接口，用于定义从Redis Stream消费消息的逻辑。
 */
@FunctionalInterface
public interface MessageConsumer {
    
    /**
     * 消息处理
     * @param message 消息
     * 
     * */
    void accept(ObjectRecord<String, String> message);
}