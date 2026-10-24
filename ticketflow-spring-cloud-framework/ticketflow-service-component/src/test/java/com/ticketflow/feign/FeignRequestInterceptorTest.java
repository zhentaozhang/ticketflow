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
import static com.ticketflow.constant.Constant.INTERNAL_TOKEN;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        FeignRequestInterceptor interceptor = new FeignRequestInterceptor("false", "");
        RequestTemplate template = new RequestTemplate();

        assertDoesNotThrow(() -> interceptor.apply(template));
        assertTrue(template.headers().isEmpty());
    }

    @Test
    void applyShouldPropagateCodeAndGrayFromRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CODE)).thenReturn("2");
        when(request.getHeader(GRAY_PARAMETER)).thenReturn("true");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        FeignRequestInterceptor interceptor = new FeignRequestInterceptor("false", "");
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertEquals(headerValue(template, CODE), "2");
        assertEquals(headerValue(template, GRAY_PARAMETER), "true");
    }

    @Test
    void applyShouldFallbackToServerGrayWhenRequestHasNoGray() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(CODE)).thenReturn("2");
        when(request.getHeader(GRAY_PARAMETER)).thenReturn(null);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        FeignRequestInterceptor interceptor = new FeignRequestInterceptor("true", "");
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertEquals(headerValue(template, GRAY_PARAMETER), "true");
    }

    @Test
    void applyShouldSwallowExceptionWhenAttributesNotServlet() {
        RequestContextHolder.setRequestAttributes(mock(RequestAttributes.class));
        FeignRequestInterceptor interceptor = new FeignRequestInterceptor("false", "");
        RequestTemplate template = new RequestTemplate();

        assertDoesNotThrow(() -> interceptor.apply(template));
        assertTrue(template.headers().isEmpty());
    }

    @Test
    void applyShouldCarryInternalTokenEvenWithoutRequestAttributes() {
        // 定时任务/异步线程没有 RequestContextHolder，但服务间调用依然必须带内部令牌
        FeignRequestInterceptor interceptor = new FeignRequestInterceptor("false", "unit-token");
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertEquals(headerValue(template, INTERNAL_TOKEN), "unit-token");
    }

    @Test
    void applyShouldSkipInternalTokenWhenNotConfigured() {
        FeignRequestInterceptor interceptor = new FeignRequestInterceptor("false", "");
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertNull(headerValue(template, INTERNAL_TOKEN));
    }

    private String headerValue(RequestTemplate template, String name) {
        Collection<String> values = template.headers().get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.iterator().next();
    }
}
