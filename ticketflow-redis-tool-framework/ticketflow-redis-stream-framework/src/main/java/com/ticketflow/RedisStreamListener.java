package com.ticketflow;

import com.ticketflow.trace.TraceContextHandle;
import com.ticketflow.trace.TraceContextUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.stream.StreamListener;

/**
 * Redis Stream监听器。实现StreamListener接口，持续消费Stream中的消息并委托给MessageConsumer处理。
 */
@Slf4j
@AllArgsConstructor
public class RedisStreamListener implements StreamListener<String, ObjectRecord<String, String>> {
    
    private final MessageConsumer messageConsumer;
    

    @Override
    public void onMessage(ObjectRecord<String, String> message) {
        RecordId messageId = message.getId();
        String rawValue = message.getValue();
        TraceContextUtil.UnwrapResult unwrapResult = TraceContextUtil.unwrap(rawValue);
        try (TraceContextHandle handle = TraceContextUtil.openScope(unwrapResult.getHeaders())) {
            log.info("redis stream 消费到了数据 messageId : {}, streamName : {}, message : {}",
                    messageId, message.getStream(), unwrapResult.getPayload());
            ObjectRecord<String, String> unwrappedRecord = StreamRecords.newRecord()
                    .in(message.getStream())
                    .withId(messageId)
                    .ofObject(unwrapResult.getPayload());
            messageConsumer.accept(unwrappedRecord);
        }
    }
}
