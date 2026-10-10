package com.ticketflow;

import com.alibaba.fastjson.JSON;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Redis Stream操作处理器。提供消息确认(ack)和消费者组管理功能。
 */
@Slf4j
@AllArgsConstructor
public class RedisStreamHandler {
    
    private final RedisStreamPushHandler redisStreamPushHandler;
    
    private final StringRedisTemplate stringRedisTemplate;
    
    public void addGroup(String streamName, String groupName){
        stringRedisTemplate.opsForStream().createGroup(streamName,groupName);
    }
    
    public Boolean hasKey(String key){
        if(Objects.isNull(key)){
            return false;
        }else{
            return stringRedisTemplate.hasKey(key);
        }
        
    }
 
    public void del(String key, RecordId recordIds){
        stringRedisTemplate.opsForStream().delete(key,recordIds);
    }
    
    public void streamBindingGroup(String streamName, String group){
        // 仅当 stream 不存在时才需要先写入一条哑消息把 stream 建出来（Redisson/Spring createGroup 不会 MKSTREAM）。
        // 无论 stream 是否已存在，都必须尝试 createGroup：
        // 旧实现只在 !hasKey 时建组，一旦 stream 还在但消费者组丢失（XTRIM/历史/组被删），
        // 消费端就永远订阅不到、消息全部堆在 stream 里。
        boolean hasKey = hasKey(streamName);
        RecordId dummyRecordId = null;
        if(!hasKey){
            Map<String,Object> map = new HashMap<>(2);
            map.put("key","value");
            dummyRecordId = redisStreamPushHandler.push(JSON.toJSONString(map));
        }
        try {
            addGroup(streamName, group);
        } catch (RuntimeException e) {
            if (!isBusyGroup(e)) {
                throw e;
            }
            log.warn("stream group already exists, skip create. streamName : {} group : {}", streamName, group);
        }
        if (Objects.nonNull(dummyRecordId)) {
            del(streamName, dummyRecordId);
        }
        log.info("initStream streamName : {} group : {}",streamName,group);
    }

    private boolean isBusyGroup(Throwable throwable){
        if(Objects.isNull(throwable)){
            return false;
        }
        if(throwable.getMessage() != null && throwable.getMessage().contains("BUSYGROUP")){
            return true;
        }
        return isBusyGroup(throwable.getCause());
    }
}
