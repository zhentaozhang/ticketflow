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
 * 将请求 Header 中的 traceId 写入 MDC，便于日志链路追踪，
 * 请求结束时清理，避免线程复用导致上下文污染。
 * 注意：traceId 的 MDC 处理与 service-component 的 BaseParameterFilter 重叠（均为幂等 put/remove）
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
