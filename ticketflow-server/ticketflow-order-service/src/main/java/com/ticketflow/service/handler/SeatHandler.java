package com.ticketflow.service.handler;

import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.servicelock.LockType;
import com.ticketflow.servicelock.annotion.ServiceLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.ticketflow.core.DistributedLockConstants.SEAT_LOCK;

/**
 * 座位数据删除 Handler。
 * 被 OrderManageService 在后台重置节目数据时调用，
 * 通过 @ServiceLock(Write) 分布式锁保护，
 * 清除 Redis 中指定节目+票档的三区座位数据（no_sold / lock / sold）
 */
@Slf4j
@Component
public class SeatHandler {
    
    @Autowired
    private RedisCache redisCache;

    /**
     * 从redis中删除座位数据
     * */
    @ServiceLock(lockType= LockType.Write,name = SEAT_LOCK,keys = {"#programId","#ticketCategoryId"})
    public void delRedisSeatData(Long programId,Long ticketCategoryId){
        List<RedisKeyBuild> keyList = new ArrayList<>();
        keyList.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_NO_SOLD_RESOLUTION_HASH,programId,ticketCategoryId));
        keyList.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_LOCK_RESOLUTION_HASH,programId,ticketCategoryId));
        keyList.add(RedisKeyBuild.createRedisKey(RedisKeyManage.PROGRAM_SEAT_SOLD_RESOLUTION_HASH,programId,ticketCategoryId));
        redisCache.del(keyList);
    }
}
