package com.ticketflow.filter;


import com.ticketflow.util.StringUtil;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import static com.ticketflow.constant.Constant.TRACE_ID;

/**
 * 链路上下文过滤器（Servlet）。
 * 在请求进入时从 Header 提取 userId、channel、grayVersion 等参数，
 * 存入 BaseParameterHolder（ThreadLocal），
 * 请求结束时清理
 */

public class RequestParamContextFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID);
        if (StringUtil.isNotEmpty(traceId)) {
            MDC.put(TRACE_ID, traceId);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID);
        }
    }
}
