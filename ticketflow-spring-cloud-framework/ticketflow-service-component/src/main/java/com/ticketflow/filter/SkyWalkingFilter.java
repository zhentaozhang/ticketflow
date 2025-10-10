package com.ticketflow.filter;

import com.ticketflow.threadlocal.BaseParameterHolder;
import com.ticketflow.util.StringUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.skywalking.apm.toolkit.trace.TraceContext;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import static com.ticketflow.constant.Constant.SKY_WALKING_TRACE_ID;

/**
 * SkyWalking链路追踪过滤器。从SkyWalking上下文中提取TraceId并放入MDC，实现日志链路追踪。
 */
public class SkyWalkingFilter extends OncePerRequestFilter {
    
    
    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain) throws ServletException, IOException {
        String skyWalkingTraceId = TraceContext.traceId();
        
        try {
            if (StringUtil.isNotEmpty(skyWalkingTraceId)) {
                BaseParameterHolder.setParameter(SKY_WALKING_TRACE_ID,skyWalkingTraceId);
                MDC.put(SKY_WALKING_TRACE_ID,skyWalkingTraceId);
            }
            filterChain.doFilter(request, response);
        }finally {
            BaseParameterHolder.removeParameter(SKY_WALKING_TRACE_ID);
            MDC.remove(SKY_WALKING_TRACE_ID);
        }
    }
}