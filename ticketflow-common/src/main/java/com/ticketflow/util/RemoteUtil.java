package com.ticketflow.util;

import org.apache.commons.lang.StringUtils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 请求来源工具。
 * 从 X-Forwarded-For 提取客户端真实 IP，拼接 UA 生成唯一标识 remoteId，
 * 用于 RateLimiter 的 Flood 全局限流识别
 */
public class RemoteUtil {
    
    public static String getRemoteId(HttpServletRequest request) {
        String forward = request.getHeader("X-Forwarded-For");
        String ip = getRemoteIpFromForward(forward);
        String ua = request.getHeader("user-agent");
        if (StringUtils.isNotBlank(ip)) {
            return ip + ua;
        }
        return request.getRemoteAddr() + ua;
    }
    
    private static String getRemoteIpFromForward(String forward) {
        if (StringUtils.isNotBlank(forward)) {
            String[] ipList = forward.split(",");
            return StringUtils.trim(ipList[0]);
        }
        return null;
    }
}
