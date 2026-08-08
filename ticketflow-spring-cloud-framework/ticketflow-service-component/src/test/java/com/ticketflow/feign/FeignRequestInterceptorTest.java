package com.ticketflow.feign;

import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collection;

import static com.ticketflow.constant.Constant.CODE;
import static com.ticketflow.constant.Constant.GRAY_PARAMETER;
import static com.ticketflow.constant.Constant.TRACE_ID;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeignRequestInterceptorTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.setRequestAttributes(null);
    }

    @Test
    void applyShouldSkipWhenNoRequestAttributes() {
        FeignRequestInterceptor interceptor = new FeignRequestInterceptor("false");
        RequestTemplate template = new RequestTemplate();

        assertDoesNotThrow(() -> interceptor.apply(template));
        assertTrue(template.headers().isEmpty());
    }

    @Test
    void applyShouldPropagateTraceIdCodeAndGrayFromRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(TRACE_ID)).thenReturn("trace-1");
        when(request.getHeader(CODE)).thenReturn("2");
        when(request.getHeader(GRAY_PARAMETER)).thenReturn("true");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        FeignRequestInterceptor interceptor = new FeignRequestInterceptor("false");
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertEquals(headerValue(template, TRACE_ID), "trace-1");
        assertEquals(headerValue(template, CODE), "2");
        assertEquals(headerValue(template, GRAY_PARAMETER), "true");
    }

    @Test
    void applyShouldFallbackToServerGrayWhenRequestHasNoGray() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(TRACE_ID)).thenReturn("trace-1");
        when(request.getHeader(CODE)).thenReturn("2");
        when(request.getHeader(GRAY_PARAMETER)).thenReturn(null);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        FeignRequestInterceptor interceptor = new FeignRequestInterceptor("true");
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertEquals(headerValue(template, GRAY_PARAMETER), "true");
    }

    @Test
    void applyShouldSwallowExceptionWhenAttributesNotServlet() {
        RequestContextHolder.setRequestAttributes(mock(RequestAttributes.class));
        FeignRequestInterceptor interceptor = new FeignRequestInterceptor("false");
        RequestTemplate template = new RequestTemplate();

        assertDoesNotThrow(() -> interceptor.apply(template));
        assertTrue(template.headers().isEmpty());
    }

    private String headerValue(RequestTemplate template, String name) {
        Collection<String> values = template.headers().get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.iterator().next();
    }
}
