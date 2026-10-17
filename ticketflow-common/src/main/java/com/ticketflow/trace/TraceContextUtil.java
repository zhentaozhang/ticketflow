package com.ticketflow.trace;

import com.alibaba.fastjson.JSON;
import com.ticketflow.constant.Constant;
import com.ticketflow.threadlocal.BaseParameterHolder;
import com.ticketflow.util.StringUtil;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 分布式链路与上下文传递工具类。
 * <p>
 * 用于在跨中间件（如 Redisson 延迟队列、Redis Stream 广播链等）时：
 * 1. 生产端将当前线程的 OTel W3C TraceContext（traceparent）、Log4j2 MDC 与业务上下文（如 gray）打包为 TraceMessageEnvelope；
 * 2. 消费端安全解包，还原 OTel 上下文与 MDC，并在消费结束后通过 AutoCloseable 机制清理线程，防止串号污染；
 * 3. 消费端自动向下兼容未包装的旧格式裸消息。
 */
@Slf4j
public final class TraceContextUtil {

    public static final String TRACEPARENT = "traceparent";
    public static final String TRACESTATE = "tracestate";
    public static final String TRACE_ID_KEY = "trace_id";
    public static final String SPAN_ID_KEY = "span_id";

    private static final TextMapSetter<Map<String, String>> MAP_SETTER = (carrier, key, value) -> {
        if (carrier != null && key != null && value != null) {
            carrier.put(key, value);
        }
    };

    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier != null ? carrier.keySet() : Collections.emptyList();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier != null ? carrier.get(key) : null;
        }
    };

    private TraceContextUtil() {
    }

    /**
     * 解包结果容器
     */
    @Data
    @AllArgsConstructor
    public static class UnwrapResult {
        private final Map<String, String> headers;
        private final String payload;
    }

    /**
     * 将业务载荷及当前线程链路/业务上下文打包为 TraceMessageEnvelope JSON 字符串。
     *
     * @param payload 原始业务消息内容
     * @return 打包后的 JSON 字符串
     */
    public static String wrap(String payload) {
        if (payload == null) {
            return null;
        }

        Map<String, String> headers = new HashMap<>(16);

        // 1. 通过 OTel TextMapPropagator 注入当前活跃 Trace 上下文
        try {
            TextMapPropagator propagator = GlobalOpenTelemetry.getPropagators().getTextMapPropagator();
            if (propagator != null) {
                propagator.inject(Context.current(), headers, MAP_SETTER);
            }
        } catch (Throwable t) {
            log.debug("OTel context inject skipped: {}", t.getMessage());
        }

        // 2. 兜底与补充：若无 traceparent 但 MDC 存在 trace_id，则构造标准 W3C traceparent (00-{traceId}-{spanId}-01)
        String traceparent = headers.get(TRACEPARENT);
        String mdcTraceId = MDC.get(TRACE_ID_KEY);
        String mdcSpanId = MDC.get(SPAN_ID_KEY);

        if (StringUtil.isEmpty(traceparent) && StringUtil.isNotEmpty(mdcTraceId)) {
            String spanId = StringUtil.isNotEmpty(mdcSpanId) ? mdcSpanId
                    : String.format("%016x", ThreadLocalRandom.current().nextLong());
            traceparent = "00-" + mdcTraceId + "-" + spanId + "-01";
            headers.put(TRACEPARENT, traceparent);
        }

        // 3. 提取或回填 trace_id 与 span_id 至 headers 供快速读取与检索
        if (StringUtil.isNotEmpty(traceparent)) {
            String[] parts = traceparent.split("-");
            if (parts.length >= 4) {
                headers.putIfAbsent(TRACE_ID_KEY, parts[1]);
                headers.putIfAbsent(SPAN_ID_KEY, parts[2]);
            }
        } else if (StringUtil.isNotEmpty(mdcTraceId)) {
            headers.putIfAbsent(TRACE_ID_KEY, mdcTraceId);
            if (StringUtil.isNotEmpty(mdcSpanId)) {
                headers.putIfAbsent(SPAN_ID_KEY, mdcSpanId);
            }
        }

        // 4. 提取业务上下文（灰度标识、用户标识等）
        String gray = BaseParameterHolder.getParameter(Constant.GRAY_PARAMETER);
        if (StringUtil.isNotEmpty(gray)) {
            headers.put(Constant.GRAY_PARAMETER, gray);
        }
        String userId = BaseParameterHolder.getParameter(Constant.USER_ID);
        if (StringUtil.isNotEmpty(userId)) {
            headers.put(Constant.USER_ID, userId);
        }

        TraceMessageEnvelope envelope = new TraceMessageEnvelope(headers, payload);
        return JSON.toJSONString(envelope);
    }

    /**
     * 解包消息。具备向下兼容嗅探能力：
     * - 若消息符合 TraceMessageEnvelope 格式，则返回 headers 与解析后的 payload；
     * - 若消息为未打包的历史裸数据，则将 headers 置为空 Map，payload 原样返回。
     *
     * @param rawMessage 接收到的原始字符串
     * @return 解包结果
     */
    public static UnwrapResult unwrap(String rawMessage) {
        if (rawMessage == null) {
            return new UnwrapResult(Collections.emptyMap(), null);
        }

        if (rawMessage.startsWith("{\"magic\":\"" + TraceMessageEnvelope.ENVELOPE_MAGIC + "\"")
                || rawMessage.contains("\"magic\":\"" + TraceMessageEnvelope.ENVELOPE_MAGIC + "\"")) {
            try {
                TraceMessageEnvelope envelope = JSON.parseObject(rawMessage, TraceMessageEnvelope.class);
                if (envelope != null && TraceMessageEnvelope.ENVELOPE_MAGIC.equals(envelope.getMagic())) {
                    Map<String, String> headers = envelope.getHeaders() != null
                            ? envelope.getHeaders() : Collections.emptyMap();
                    return new UnwrapResult(headers, envelope.getPayload());
                }
            } catch (Exception e) {
                log.warn("解析 TraceMessageEnvelope 异常，降级为裸消息处理 rawMessage : {}", rawMessage, e);
            }
        }

        return new UnwrapResult(Collections.emptyMap(), rawMessage);
    }

    /**
     * 在当前工作线程绑定链路与上下文作用域。
     * 支持 try-with-resources 语法，在 close() 时还原线程历史上下文。
     *
     * @param headers 消息携带的链路与业务元数据
     * @return AutoCloseable 上下文句柄
     */
    public static TraceContextHandle openScope(Map<String, String> headers) {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        Map<String, String> currentHold = BaseParameterHolder.getThreadLocal().get();
        Map<String, String> previousHold = currentHold != null ? new HashMap<>(currentHold) : null;

        if (headers == null || headers.isEmpty()) {
            return new TraceContextHandle(null, previousMdc, previousHold);
        }

        Scope otelScope = null;
        try {
            TextMapPropagator propagator = GlobalOpenTelemetry.getPropagators().getTextMapPropagator();
            if (propagator != null) {
                Context extracted = propagator.extract(Context.root(), headers, MAP_GETTER);
                if (extracted != null) {
                    otelScope = extracted.makeCurrent();
                }
            }
        } catch (Throwable t) {
            log.debug("OTel context extract skipped: {}", t.getMessage());
        }

        // 还原 MDC
        String traceId = headers.get(TRACE_ID_KEY);
        String spanId = headers.get(SPAN_ID_KEY);
        String traceparent = headers.get(TRACEPARENT);

        if (StringUtil.isEmpty(traceId) && StringUtil.isNotEmpty(traceparent)) {
            String[] parts = traceparent.split("-");
            if (parts.length >= 4) {
                traceId = parts[1];
                if (StringUtil.isEmpty(spanId)) {
                    spanId = parts[2];
                }
            }
        }

        if (StringUtil.isNotEmpty(traceId)) {
            MDC.put(TRACE_ID_KEY, traceId);
        }
        if (StringUtil.isNotEmpty(spanId)) {
            MDC.put(SPAN_ID_KEY, spanId);
        }

        String gray = headers.get(Constant.GRAY_PARAMETER);
        if (StringUtil.isNotEmpty(gray)) {
            MDC.put(Constant.GRAY_PARAMETER, gray);
            BaseParameterHolder.setParameter(Constant.GRAY_PARAMETER, gray);
        }

        String userId = headers.get(Constant.USER_ID);
        if (StringUtil.isNotEmpty(userId)) {
            BaseParameterHolder.setParameter(Constant.USER_ID, userId);
        }

        return new TraceContextHandle(otelScope, previousMdc, previousHold);
    }
}
