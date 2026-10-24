package com.ticketflow.filter;

import com.ticketflow.constant.Constant;
import com.ticketflow.util.StringUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 下游服务「内部令牌」网关过滤器。
 * <p>
 * 背景：网关是本项目的唯一安全边界（验签、渠道、token、身份注入都在网关完成），但服务端口
 * 一旦被直接暴露（compose 端口映射、K8s NodePort/Service 误配、内网横向移动），攻击者可以
 * 完全绕过网关直接调用 {@code /program/interior/**}、{@code /program/record/task/**} 这类
 * 「只允许服务间调用」的接口。本过滤器在所有基于 ticketflow-service-component 的服务里生效，
 * 要求每个非探活请求都必须带网关/服务端注入的 {@link Constant#INTERNAL_TOKEN} 头。
 * <p>
 * 设计取舍：
 * 1) 未配置令牌时一律拒绝（fail closed）：空令牌放行等于永久后门；
 * 2) 用 {@link MessageDigest#isEqual} 定长时间比较，避免按字节短路泄露令牌前缀；
 * 3) 探活/指标/文档端点白名单放行，保证 K8s probe、Prometheus 抓取与 knife4j 聚合不被误杀；
 * 4) 令牌只存在于服务端配置（环境变量 INTERNAL_TOKEN），前端与外部客户端无法获得。
 */
@Slf4j
public class InternalTokenFilter extends OncePerRequestFilter {

    /**
     * 免鉴权端点前缀：容器 healthcheck、K8s liveness/readiness、Prometheus 抓取、接口文档。
     * 其余路径（含所有业务接口与 /program/interior 内部接口）都必须带内部令牌。
     */
    private static final List<String> SKIP_PREFIXES = List.of(
            "/actuator/health",
            "/actuator/info",
            "/actuator/prometheus",
            "/error",
            "/doc.html",
            "/v3/api-docs",
            "/swagger-ui",
            "/swagger-resources",
            "/knife4j",
            "/webjars/",
            "/favicon.ico"
    );

    private final String internalToken;

    public InternalTokenFilter(String internalToken) {
        this.internalToken = internalToken;
    }

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (StringUtil.isEmpty(uri)) {
            return false;
        }
        for (String prefix : SKIP_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {
        String provided = request.getHeader(Constant.INTERNAL_TOKEN);
        if (StringUtil.isEmpty(internalToken)) {
            log.error("下游服务拒绝访问：ticketflow.internal.token 未配置，uri={}", request.getRequestURI());
            reject(response);
            return;
        }
        if (StringUtil.isEmpty(provided) || !MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            log.warn("缺少或错误的内部令牌，uri={} remote={}", request.getRequestURI(), request.getRemoteAddr());
            reject(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void reject(final HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":520,\"message\":\"内部接口禁止外部直接访问\",\"data\":null}");
        response.getWriter().flush();
    }
}
