package com.ticketflow.filter;

import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.ticketflow.properties.BackManageProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackManageAuthFilterTest {

    @Test
    void shouldPassThroughWhenNotBackManageRequest() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("back_manage")).thenReturn(null);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        BackManageAuthFilter filter = new BackManageAuthFilter(new BackManageProperties());

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).getWriter();
    }

    @Test
    void shouldPassWhenUriInLoginExcludeApi() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("back_manage")).thenReturn("true");
        when(request.getRequestURI()).thenReturn("/auth/login");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        BackManageAuthFilter filter = new BackManageAuthFilter(new BackManageProperties());

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldPassWhenLoggedIn() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("back_manage")).thenReturn("true");
        when(request.getRequestURI()).thenReturn("/admin/list");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        BackManageAuthFilter filter = new BackManageAuthFilter(new BackManageProperties());

        StpLogic oldLogic = StpUtil.stpLogic;
        try {
            StpUtil.setStpLogic(new StpLogic("login") {
                @Override
                public boolean isLogin() {
                    return true;
                }
            });
            filter.doFilter(request, response, chain);
        } finally {
            StpUtil.setStpLogic(oldLogic);
        }

        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldRejectWhenNotLoggedIn() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("back_manage")).thenReturn("true");
        when(request.getRequestURI()).thenReturn("/admin/list");
        HttpServletResponse response = mock(HttpServletResponse.class);
        PrintWriter writer = mock(PrintWriter.class);
        when(response.getWriter()).thenReturn(writer);
        FilterChain chain = mock(FilterChain.class);
        BackManageAuthFilter filter = new BackManageAuthFilter(new BackManageProperties());

        StpLogic oldLogic = StpUtil.stpLogic;
        try {
            StpUtil.setStpLogic(new StpLogic("login") {
                @Override
                public boolean isLogin() {
                    return false;
                }
            });
            filter.doFilter(request, response, chain);
        } finally {
            StpUtil.setStpLogic(oldLogic);
        }

        verify(chain, never()).doFilter(request, response);
        verify(response).setCharacterEncoding("UTF-8");
        verify(writer).print(org.mockito.ArgumentMatchers.anyString());
    }
}
