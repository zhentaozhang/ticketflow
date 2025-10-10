package com.ticketflow.filter;

import com.ticketflow.request.CustomizeRequestWrapper;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Request 包装过滤器：将原始 request 包装为 CustomizeRequestWrapper，
 * 使得 request body 可重复读取（ServletInputStream 默认只能读一次）。
 *
 * 下游 BaseParameterFilter 需要读取 body 内容校验签名，
 * 之后业务 Controller 也需要读取 body，没有此包装会抛出 IllegalStateException
 */
public class RequestWrapperFilter extends OncePerRequestFilter {
    
    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, 
                                    final FilterChain filterChain) throws ServletException, IOException {
        CustomizeRequestWrapper requestWrapper = new CustomizeRequestWrapper(request);
        filterChain.doFilter(requestWrapper,response);
    }
}
