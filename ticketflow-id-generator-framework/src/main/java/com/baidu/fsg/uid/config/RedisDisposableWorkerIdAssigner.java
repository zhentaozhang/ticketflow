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
    
    private static final int MAX_RETRY_TIMES = 3;

    @Override
    public long assignWorkerId() {
        // Redis 短暂不可用容忍：退避重试，避免部署窗口 Redis 重启导致启动失败
        for (int i = 1; i <= MAX_RETRY_TIMES; i++) {
            try {
                String key = "uid_work_id";
                Long increment = redisTemplate.opsForValue().increment(key);
                return Optional.ofNullable(increment).orElseThrow(() -> new TicketFlowFrameException(BaseCode.UID_WORK_ID_ERROR));
            } catch (TicketFlowFrameException e) {
                throw e;
            } catch (Exception e) {
                if (i == MAX_RETRY_TIMES) {
                    throw new TicketFlowFrameException(e);
                }
                try {
                    Thread.sleep(1000L * i);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new TicketFlowFrameException(ie);
                }
            }
        }
        throw new TicketFlowFrameException(BaseCode.UID_WORK_ID_ERROR);
    }
}
