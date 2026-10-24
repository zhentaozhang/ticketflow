package com.ticketflow.filter;

import com.ticketflow.constant.Constant;
import com.ticketflow.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 网关「内部接口封口」过滤器（WebFilter，先于所有 GatewayFilter 执行）。
 * <p>
 * 做两件事：
 * 1) 显式封禁只允许服务间调用的路径前缀，外部请求一律 404，让「能访问网关」不再等于
 *    能调用 {@code /program/interior/**}（改库存/锁座）、{@code /program/record/task/**}（改对账记录）、
 *    {@code /channel/data/**}（渠道私钥）与 {@code /message/record/**}；
 * 2) 给所有转发到下游的请求注入内部令牌 {@link Constant#INTERNAL_TOKEN}，覆盖客户端自带值，
 *    配合下游 InternalTokenFilter 形成「网关之外无入口」的闭环。
 * <p>
 * 路径匹配说明：网关路由带 {@code StripPrefix=2}，形如 {@code /ticketflow/program/program/interior/x}
 * 在剥掉服务前缀后等价于 {@code /program/interior/x}，因此先还原下游视角的路径再比对，
 * 避免攻击者用不同前缀拼出同一内部接口。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class InternalAccessFilter implements WebFilter {

    /** 网关对外路由前缀，与 application-pro.yml 中各路由 predicate 一致 */
    private static final String SERVICE_PATH_PREFIX = "/ticketflow/";

    /** 只允许服务间调用的下游路径前缀（已剥离 /ticketflow/{service} 前缀） */
    private static final List<String> INTERNAL_PATH_PREFIXES = List.of(
            "/program/interior/",
            "/program/record/task/",
            "/channel/data/",
            "/message/record/"
    );

    /** 网关自身 actuator 仅开放探活与指标，其余（env/beans/heapdump/configprops...）一律 404 */
    private static final List<String> ACTUATOR_ALLOWED_PREFIXES = List.of(
            "/actuator/health",
            "/actuator/info",
            "/actuator/prometheus"
    );

    private final String internalToken;

    public InternalAccessFilter(@Value("${ticketflow.internal.token:}") String internalToken) {
        this.internalToken = internalToken;
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (isBlocked(path)) {
            log.warn("网关拦截内部接口直连 path={} remote={}", path,
                    exchange.getRequest().getRemoteAddress());
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        // 先剥离客户端伪造的 internalToken，再注入服务端权威令牌
        ServerHttpRequest request = exchange.getRequest().mutate().headers(headers -> {
            headers.remove(Constant.INTERNAL_TOKEN);
            if (StringUtil.isNotEmpty(internalToken)) {
                headers.set(Constant.INTERNAL_TOKEN, internalToken);
            }
        }).build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    private boolean isBlocked(final String path) {
        if (path.startsWith("/actuator")) {
            for (String allowed : ACTUATOR_ALLOWED_PREFIXES) {
                if (path.startsWith(allowed)) {
                    return false;
                }
            }
            return true;
        }
        String downstreamPath = toDownstreamPath(path);
        for (String prefix : INTERNAL_PATH_PREFIXES) {
            if (downstreamPath.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把 {@code /ticketflow/{service}/{rest}} 还原成下游视角的 {@code /{rest}}。
     * 不含 {@code /ticketflow/} 前缀的路径原样返回（未走网关路由的路径不会被放行到内部接口）。
     */
    static String toDownstreamPath(final String path) {
        if (StringUtil.isEmpty(path) || !path.startsWith(SERVICE_PATH_PREFIX)) {
            return path;
        }
        int serviceEnd = path.indexOf('/', SERVICE_PATH_PREFIX.length());
        if (serviceEnd < 0) {
            return path;
        }
        return path.substring(serviceEnd);
    }
}
