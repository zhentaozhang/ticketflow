package com.ticketflow.filter;

import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.properties.BackManageProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * 后台管理登录态过滤器（Sa-Token）。
 * 检查请求 header 中 back_manage=true，如果是则校验登录状态：
 *   白名单路径（loginExcludeApi）直接放行
 *   其他路径通过 StpUtil.isLogin() 验证 Sa-Token 登录
 *
 * 与前端业务接口隔离——普通用户请求（无 back_manage header）直接跳过
 */
public class BackManageAuthFilter extends OncePerRequestFilter {
    
    private String trueStr = "true";
    
    private final BackManageProperties backManageProperties;
    
    public BackManageAuthFilter(BackManageProperties backManageProperties) {
        this.backManageProperties = backManageProperties;
    }
    
    @Override
    protected void doFilterInternal(@NonNull final HttpServletRequest request, 
                                    @NonNull final HttpServletResponse response,
                                    @NonNull final FilterChain filterChain) throws ServletException, IOException {
        String backManage = request.getHeader("back_manage");
        if (!trueStr.equals(backManage)) {
            filterChain.doFilter(request, response);
            return;
        }
        boolean pass = false;
        String requestUri = request.getRequestURI();
        //如果是登录请求，则可以放行
        if (backManageProperties.getLoginExcludeApi().contains(requestUri)) {
            pass = true;
        }else {
            //检查登录状态
            if (StpUtil.isLogin()){
                //用户已登录，放行
                pass = true;
            }
        }
        if (pass) {
            //放行
            filterChain.doFilter(request, response);
        }else {
            //用户未登录，返回错误码
            response.setCharacterEncoding("UTF-8");
            response.setContentType("text/html; charset=utf-8");
            try (PrintWriter writer = response.getWriter()) {
                JSONObject jsonObject = new JSONObject();
                writer.print(JSON.toJSONString(ApiResponse.error(BaseCode.USER_NOT_LOGIN)));
            }
        }
    }
}
