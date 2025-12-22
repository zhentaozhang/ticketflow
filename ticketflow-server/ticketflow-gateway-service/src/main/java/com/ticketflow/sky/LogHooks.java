package com.ticketflow.sky;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Operators;

/**
 * 响应式日志钩子。注册MDC上下文操作符，在网关响应式流中维护日志追踪ID。
 */
@Component
public class LogHooks {

    private static final String KEY = "logMdc";

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void setHook() {
        reactor.core.publisher.Hooks.onEachOperator(KEY,
                Operators.lift((scannable, coreSubscriber) -> new MdcSubscriber(coreSubscriber)));
    }

    @PreDestroy
    public void resetHook() {
        reactor.core.publisher.Hooks.resetOnEachOperator(KEY);
    }

}