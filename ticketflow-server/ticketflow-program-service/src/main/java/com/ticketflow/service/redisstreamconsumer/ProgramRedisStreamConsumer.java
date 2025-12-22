package com.ticketflow.service.redisstreamconsumer;

import com.ticketflow.MessageConsumer;
import com.ticketflow.service.ProgramService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.stereotype.Component;

/**
 * Redis Stream 消费者：监听节目数据变更，失效本地缓存。
 * 当节目数据通过后台维护或订单操作变更时，program-service 向 Redis Stream 写入
 * 节目 ID → ProgramRedisStreamConsumer 消费 → 调用 programService.delLocalCache()
 *
 * 保证集群模式下所有实例的本地 Caffeine 缓存最终一致。
 */
@Slf4j
@Component
public class ProgramRedisStreamConsumer implements MessageConsumer {
    
    @Autowired
    private ProgramService programService;
    
    /**
     * 消费 Redis Stream 消息，失效指定节目的本地 Caffeine 缓存。
     * 当节目数据变更时，其他服务写入 Redis Stream → 本消费者收到消息 →
     * 调用 programService.delLocalCache(programId) 使当前实例的本地缓存失效。
     *
     * @param message Redis Stream 消息，value 为节目 ID 的字符串表示
     */
    @Override
    public void accept(ObjectRecord<String, String> message) {
        Long programId = Long.parseLong(message.getValue());
        programService.delLocalCache(programId);
    }
}
