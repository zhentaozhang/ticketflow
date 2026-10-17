package com.ticketflow;

import com.ticketflow.constant.Constant;
import com.ticketflow.threadlocal.BaseParameterHolder;
import com.ticketflow.trace.TraceContextUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RedisStreamListenerTest {

    @BeforeEach
    @AfterEach
    void cleanup() {
        MDC.clear();
        BaseParameterHolder.removeParameterMap();
    }

    @Test
    void testOnMessageWithEnvelopedTrace() {
        String testTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String testSpanId = "00f067aa0ba902b7";

        MDC.put(TraceContextUtil.TRACE_ID_KEY, testTraceId);
        MDC.put(TraceContextUtil.SPAN_ID_KEY, testSpanId);
        BaseParameterHolder.setParameter(Constant.GRAY_PARAMETER, "true");

        String originalProgramId = "1001";
        String wrappedMessage = TraceContextUtil.wrap(originalProgramId);

        // 清理当前线程，模拟消费端独立线程
        MDC.clear();
        BaseParameterHolder.removeParameterMap();

        AtomicReference<String> receivedPayload = new AtomicReference<>();
        AtomicReference<String> receivedMdcTraceId = new AtomicReference<>();
        AtomicReference<String> receivedGray = new AtomicReference<>();

        MessageConsumer mockConsumer = message -> {
            receivedPayload.set(message.getValue());
            receivedMdcTraceId.set(MDC.get(TraceContextUtil.TRACE_ID_KEY));
            receivedGray.set(BaseParameterHolder.getParameter(Constant.GRAY_PARAMETER));
        };

        RedisStreamListener listener = new RedisStreamListener(mockConsumer);
        ObjectRecord<String, String> incomingRecord = StreamRecords.newRecord()
                .in("invalid_program")
                .withId(RecordId.of("1700000000000-0"))
                .ofObject(wrappedMessage);

        listener.onMessage(incomingRecord);

        // 验证消费业务收到的是解包后的真实 payload
        assertEquals("1001", receivedPayload.get());
        // 验证消费时 MDC 与灰度参数已被正确还原
        assertEquals(testTraceId, receivedMdcTraceId.get());
        assertEquals("true", receivedGray.get());

        // 验证消费结束后线程上下文被清理，防止复用污染
        assertNull(MDC.get(TraceContextUtil.TRACE_ID_KEY));
        assertNull(BaseParameterHolder.getParameter(Constant.GRAY_PARAMETER));
    }

    @Test
    void testOnMessageWithLegacyRawPayload() {
        // 模拟老版本历史裸消息
        String legacyRawPayload = "2002";

        AtomicReference<String> receivedPayload = new AtomicReference<>();
        MessageConsumer mockConsumer = message -> {
            receivedPayload.set(message.getValue());
        };

        RedisStreamListener listener = new RedisStreamListener(mockConsumer);
        ObjectRecord<String, String> incomingRecord = StreamRecords.newRecord()
                .in("invalid_program")
                .withId(RecordId.of("1700000000000-1"))
                .ofObject(legacyRawPayload);

        listener.onMessage(incomingRecord);

        assertNotNull(receivedPayload.get());
        assertEquals("2002", receivedPayload.get());
    }
}
