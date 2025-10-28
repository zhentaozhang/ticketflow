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
        boolean hasKey = hasKey(streamName);
        if(!hasKey){
            Map<String,Object> map = new HashMap<>(2);
            map.put("key","value");
            RecordId recordId = redisStreamPushHandler.push(JSON.toJSONString(map));
            addGroup(streamName,group);
            del(streamName,recordId);
            log.info("initStream streamName : {} group : {}",streamName,group);
        }
    }
}
