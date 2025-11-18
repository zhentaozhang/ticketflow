package com.baidu.fsg.uid.config;

import com.baidu.fsg.uid.worker.WorkerIdAssigner;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Optional;

/**
 * Redis版WorkerId分配器。基于百度UID生成器的WorkerIdAssigner接口实现，
 * 使用Redis分配和管理分布式节点ID(workId)，避免重复。
 */
public class RedisDisposableWorkerIdAssigner implements WorkerIdAssigner {
    
    private RedisTemplate redisTemplate;
    
    public RedisDisposableWorkerIdAssigner (RedisTemplate redisTemplate){
        this.redisTemplate = redisTemplate;
    }
    
    @Override
    public long assignWorkerId() {
        String key = "uid_work_id";
        Long increment = redisTemplate.opsForValue().increment(key);
        return Optional.ofNullable(increment).orElseThrow(() -> new TicketFlowFrameException(BaseCode.UID_WORK_ID_ERROR));
    }
}
