package com.ticketflow.filter;

import com.ticketflow.threadlocal.BaseParameterHolder;
import com.ticketflow.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import static com.ticketflow.constant.Constant.CODE;
import static com.ticketflow.constant.Constant.GRAY_PARAMETER;
import static com.ticketflow.constant.Constant.USER_ID;

/**
 * 下游微服务接收端过滤器。
 * <p>
 * 作用：
 * 1. 接收上游 Gateway 或其他微服务传递过来的业务公共请求参数（灰度标识/用户信息/渠道等）。
 * 2. 将参数保存到当前线程上下文（ThreadLocal + MDC）。
 * 3. 请求结束后清理上下文，避免线程复用导致数据污染。
 * <p>
 * 调用链路：
 * <p>
 * Gateway
 * ↓
 * 注入 Header 参数(userId/gray/code)
 * ↓
 * Feign 调用其他微服务
 * ↓
 * 当前 Filter 获取 Header
 * ↓
 * ThreadLocal + MDC 保存上下文
 *
 * 说明：链路追踪上下文（trace_id/span_id）由 OpenTelemetry Java Agent 注入 MDC，
 * 本过滤器只负责 userId/gray/code 等业务上下文。
 */

// BaseParameterFilter 是微服务中的业务上下文传递过滤器，继承 OncePerRequestFilter，
// 在请求进入服务时从 Header 中获取 Gateway 传递的 userId、灰度标识、渠道等公共参数，
// 保存到 ThreadLocal 供业务代码使用，并写入 MDC 供日志过滤。
// 请求结束后通过 finally 清理 ThreadLocal 和 MDC，避免线程复用导致的数据污染和内存泄漏。
// 注：链路追踪上下文（trace_id/span_id）由 OpenTelemetry Java Agent 注入，不在此处理。
@Slf4j
public class BaseParameterFilter extends OncePerRequestFilter {

    /**
     * 每次 HTTP 请求进入时执行。
     * <p>
     * 执行流程：
     * <p>
     * 1. 获取请求中的公共参数。
     * 2. 保存到 ThreadLocal 和 MDC。
     * 3. 放行请求，继续执行 Controller。
     * 4. finally 中清理线程上下文。
     */
    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain) throws ServletException, IOException {

        /*
         * 获取请求输入流。
         *
         * ServletInputStream 用于读取 HTTP 请求 Body。
         */
        ServletInputStream sis = request.getInputStream();


        /*
         * 将请求 Body 转换成字符串。
         */
        String requestBody = StringUtil.inputStreamConvertString(sis);


        /*
         * 如果请求 Body 不为空，
         * 去除空格和换行符。
         */
        if (StringUtil.isNotEmpty(requestBody)) {
            requestBody = requestBody.replaceAll(" ", "").replaceAll("\r\n", "");
        }


        /*
         * 从 HTTP Header 中获取上游传递的公共参数。
         *
         * 这些参数通常由 Gateway 或 Feign 拦截器进行传递。
         */

        // 灰度发布标识，用于灰度流量控制
        String gray = request.getHeader(GRAY_PARAMETER);

        // 当前登录用户 ID
        String userId = request.getHeader(USER_ID);

        // 请求来源渠道标识
        String code = request.getHeader(CODE);


        try {

            /*
             * 保存灰度标识。
             *
             * BaseParameterHolder：保存当前请求线程中的业务公共参数。
             * MDC：写入日志上下文，便于日志按灰度标识过滤。
             */
            if (StringUtil.isNotEmpty(gray)) {

                BaseParameterHolder.setParameter(GRAY_PARAMETER, gray);

                MDC.put(GRAY_PARAMETER, gray);
            }


            /*
             * 保存用户信息。
             */
            if (StringUtil.isNotEmpty(userId)) {

                BaseParameterHolder.setParameter(USER_ID, userId);

                MDC.put(USER_ID, userId);
            }


            /*
             * 保存渠道信息。
             */
            if (StringUtil.isNotEmpty(code)) {

                BaseParameterHolder.setParameter(CODE, code);

                MDC.put(CODE, code);
            }


            /*
             * 放行请求。
             *
             * 当前 Filter 执行完成后，
             * 请求继续进入后续 Filter、Controller、Service。
             */
            filterChain.doFilter(request, response);


        } finally {

            /*
             * 请求处理完成后清理上下文。
             *
             * 原因：
             *
             * Tomcat 使用线程池。
             * 一个线程处理完当前请求后，
             * 可能继续处理其他请求。
             *
             * 如果 ThreadLocal 中的数据不删除，
             * 后续请求可能读取到之前请求的数据。
             */

            // 清理灰度参数
            BaseParameterHolder.removeParameter(GRAY_PARAMETER);
            MDC.remove(GRAY_PARAMETER);


            // 清理用户信息
            BaseParameterHolder.removeParameter(USER_ID);
            MDC.remove(USER_ID);


            // 清理渠道信息
            BaseParameterHolder.removeParameter(CODE);
            MDC.remove(CODE);
        }
    }
}