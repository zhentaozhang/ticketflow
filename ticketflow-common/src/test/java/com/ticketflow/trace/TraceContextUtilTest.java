package com.ticketflow.trace;

import com.ticketflow.constant.Constant;
import com.ticketflow.threadlocal.BaseParameterHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceContextUtilTest {

    @BeforeEach
    @AfterEach
    void cleanContext() {
        MDC.clear();
        BaseParameterHolder.removeParameterMap();
    }

    @Test
    void testWrapAndUnwrapWithContext() {
        String testTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String testSpanId = "00f067aa0ba902b7";
        MDC.put(TraceContextUtil.TRACE_ID_KEY, testTraceId);
        MDC.put(TraceContextUtil.SPAN_ID_KEY, testSpanId);
        BaseParameterHolder.setParameter(Constant.GRAY_PARAMETER, "true");
        BaseParameterHolder.setParameter(Constant.USER_ID, "user-999");

        String originalPayload = "{\"orderNumber\":1001,\"programId\":2002}";
        String wrapped = TraceContextUtil.wrap(originalPayload);

        assertNotNull(wrapped);
        assertTrue(wrapped.contains(TraceMessageEnvelope.ENVELOPE_MAGIC));

        TraceContextUtil.UnwrapResult unwrapResult = TraceContextUtil.unwrap(wrapped);
        assertNotNull(unwrapResult);
        assertEquals(originalPayload, unwrapResult.getPayload());

        Map<String, String> headers = unwrapResult.getHeaders();
        assertNotNull(headers);
        assertEquals(testTraceId, headers.get(TraceContextUtil.TRACE_ID_KEY));
        assertEquals(testSpanId, headers.get(TraceContextUtil.SPAN_ID_KEY));
        assertEquals("true", headers.get(Constant.GRAY_PARAMETER));
        assertEquals("user-999", headers.get(Constant.USER_ID));
        assertNotNull(headers.get(TraceContextUtil.TRACEPARENT));
        assertTrue(headers.get(TraceContextUtil.TRACEPARENT).contains(testTraceId));
    }

    @Test
    void testOpenScopeAndAutoClose() {
        String testTraceId = "12345678901234567890123456789012";
        String testSpanId = "1234567890123456";

        MDC.put(TraceContextUtil.TRACE_ID_KEY, "old-trace-id");
        BaseParameterHolder.setParameter(Constant.GRAY_PARAMETER, "old-gray");

        Map<String, String> headers = Map.of(
                TraceContextUtil.TRACE_ID_KEY, testTraceId,
                TraceContextUtil.SPAN_ID_KEY, testSpanId,
                Constant.GRAY_PARAMETER, "new-gray",
                Constant.USER_ID, "new-user"
        );

        try (TraceContextHandle handle = TraceContextUtil.openScope(headers)) {
            assertNotNull(handle);
            assertEquals(testTraceId, MDC.get(TraceContextUtil.TRACE_ID_KEY));
            assertEquals(testSpanId, MDC.get(TraceContextUtil.SPAN_ID_KEY));
            assertEquals("new-gray", MDC.get(Constant.GRAY_PARAMETER));
            assertEquals("new-gray", BaseParameterHolder.getParameter(Constant.GRAY_PARAMETER));
            assertEquals("new-user", BaseParameterHolder.getParameter(Constant.USER_ID));
        }

        // 验证退出 Scope 后恢复历史上下文
        assertEquals("old-trace-id", MDC.get(TraceContextUtil.TRACE_ID_KEY));
        assertNull(MDC.get(TraceContextUtil.SPAN_ID_KEY));
        assertEquals("old-gray", BaseParameterHolder.getParameter(Constant.GRAY_PARAMETER));
        assertNull(BaseParameterHolder.getParameter(Constant.USER_ID));
    }

    @Test
    void testUnwrapLegacyRawJsonMessage() {
        // 模拟未打包的历史订单取消 JSON 消息
        String legacyJson = "{\"messageTraceId\":12345,\"messageId\":67890,\"orderNumber\":999}";
        TraceContextUtil.UnwrapResult result = TraceContextUtil.unwrap(legacyJson);

        assertNotNull(result);
        assertEquals(legacyJson, result.getPayload());
        assertTrue(result.getHeaders().isEmpty());
    }

    @Test
    void testUnwrapLegacyRawStringMessage() {
        // 模拟未打包的历史 Redis Stream 节目 ID 纯字符串
        String legacyString = "1001";
        TraceContextUtil.UnwrapResult result = TraceContextUtil.unwrap(legacyString);

        assertNotNull(result);
        assertEquals("1001", result.getPayload());
        assertTrue(result.getHeaders().isEmpty());
    }

    @Test
    void testWrapAndUnwrapNullOrEmpty() {
        assertNull(TraceContextUtil.wrap(null));

        TraceContextUtil.UnwrapResult nullResult = TraceContextUtil.unwrap(null);
        assertNull(nullResult.getPayload());
        assertTrue(nullResult.getHeaders().isEmpty());

        TraceContextUtil.UnwrapResult emptyResult = TraceContextUtil.unwrap("");
        assertEquals("", emptyResult.getPayload());
        assertTrue(emptyResult.getHeaders().isEmpty());
    }
}
