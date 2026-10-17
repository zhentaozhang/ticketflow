package com.ticketflow.trace;

import com.ticketflow.threadlocal.BaseParameterHolder;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

/**
 * 链路与参数上下文句柄。
 * <p>
 * 支持 try-with-resources 语法，在关闭时恢复或清理线程池工作线程的 MDC、ThreadLocal 及 OTel Scope，
 * 杜绝线程池复用导致的串号污染。
 */
@Slf4j
public class TraceContextHandle implements AutoCloseable {

    private final Scope otelScope;
    private final Map<String, String> previousMdc;
    private final Map<String, String> previousHold;

    public TraceContextHandle(Scope otelScope, Map<String, String> previousMdc, Map<String, String> previousHold) {
        this.otelScope = otelScope;
        this.previousMdc = previousMdc;
        this.previousHold = previousHold;
    }

    @Override
    public void close() {
        try {
            if (otelScope != null) {
                otelScope.close();
            }
        } catch (Exception e) {
            log.warn("关闭 OTel Scope 异常", e);
        } finally {
            if (previousMdc == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previousMdc);
            }

            if (previousHold == null) {
                BaseParameterHolder.removeParameterMap();
            } else {
                BaseParameterHolder.setParameterMap(new HashMap<>(previousHold));
            }
        }
    }
}
