package com.ticketflow.filter;

import com.ticketflow.threadlocal.BaseParameterHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;

import static com.ticketflow.constant.Constant.CODE;
import static com.ticketflow.constant.Constant.GRAY_PARAMETER;
import static com.ticketflow.constant.Constant.TRACE_ID;
import static com.ticketflow.constant.Constant.USER_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BaseParameterFilterTest {

    @Test
    void shouldCaptureAndCleanContextAroundChain() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(TRACE_ID)).thenReturn("trace-1");
        when(request.getHeader(GRAY_PARAMETER)).thenReturn("true");
        when(request.getHeader(USER_ID)).thenReturn("user-1");
        when(request.getHeader(CODE)).thenReturn("2");
        when(request.getInputStream()).thenReturn(emptyInputStream());
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        doAnswer(invocation -> {
            assertEquals("trace-1", BaseParameterHolder.getParameter(TRACE_ID));
            assertEquals("true", BaseParameterHolder.getParameter(GRAY_PARAMETER));
            assertEquals("user-1", BaseParameterHolder.getParameter(USER_ID));
            assertEquals("2", BaseParameterHolder.getParameter(CODE));
            assertEquals("trace-1", MDC.get(TRACE_ID));
            return null;
        }).when(chain).doFilter(request, response);

        new BaseParameterFilter().doFilter(request, response, chain);

        assertNull(BaseParameterHolder.getParameter(TRACE_ID));
        assertNull(BaseParameterHolder.getParameter(GRAY_PARAMETER));
        assertNull(BaseParameterHolder.getParameter(USER_ID));
        assertNull(BaseParameterHolder.getParameter(CODE));
        assertNull(MDC.get(TRACE_ID));
    }

    @Test
    void shouldSkipEmptyHeadersAndStillClean() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getInputStream()).thenReturn(emptyInputStream());
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        doAnswer(invocation -> {
            assertNull(BaseParameterHolder.getParameter(TRACE_ID));
            return null;
        }).when(chain).doFilter(request, response);

        new BaseParameterFilter().doFilter(request, response, chain);

        assertNull(BaseParameterHolder.getParameter(TRACE_ID));
        assertNull(MDC.get(TRACE_ID));
    }

    private ServletInputStream emptyInputStream() {
        return new ServletInputStream() {
            @Override
            public int read() {
                return -1;
            }

            @Override
            public boolean isFinished() {
                return true;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(jakarta.servlet.ReadListener readListener) {
            }
        };
    }
}
