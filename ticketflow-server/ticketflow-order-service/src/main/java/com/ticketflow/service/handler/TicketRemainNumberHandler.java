package com.ticketflow.service.handler;

import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.servicelock.annotion.ServiceLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.ticketflow.core.DistributedLockConstants.REMAIN_NUMBER_LOCK;

/**
 * 余票数据删除 Handler。
 * 被 OrderManageService 在后台重置节目数据时调用，
 * 通过 @ServiceLock(Write) 分布式锁保护，
 * 清除 Redis 中指定节目+票档的余票 Hash
 */
@Slf4j
@Component
public class TicketRemainNumberHandler {
    
    @Autowired
    private RedisCache redisCache;

    /**
     * 从redis中删除余票数据
     * */
    @ServiceLock(lockType= LockType.Write,name = REMAIN_NUMBER_LOCK,keys = {"#programId","#ticketCategoryId"})
    public void delRedisSeatData(Long programId,Long ticketCategoryId){
        redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_TICKET_REMAIN_NUMBER_HASH_RESOLUTION,programId,ticketCategoryId));
    }
}
