package com.ticketflow.trace;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 分布式链路与上下文消息信封。
 * <p>
 * 用于跨中间件（如 Redisson 延迟队列、Redis Stream 广播链等）传递 W3C TraceContext 及业务上下文。
 * 携带 magic 标识用于消费端安全嗅探，实现新老消息向下兼容。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TraceMessageEnvelope implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 信封特征标识，用于消费端判断是否为打包过的信封消息。
     */
    public static final String ENVELOPE_MAGIC = "__TF_TRACE_ENV__";

    /**
     * 标记当前消息为信封对象
     */
    private String magic = ENVELOPE_MAGIC;

    /**
     * 链路元数据 Header：
     * - traceparent: W3C Trace Context 标准请求头 (00-{trace_id}-{span_id}-{trace_flags})
     * - tracestate: W3C 扩展
     * - trace_id / span_id: MDC 快速索引
     * - gray: 灰度标识
     * - userId: 用户标识
     */
    private Map<String, String> headers = new HashMap<>();

    /**
     * 实际业务消息内容（JSON 字符串或普通文本）
     */
    private String payload;

    public TraceMessageEnvelope(Map<String, String> headers, String payload) {
        this.magic = ENVELOPE_MAGIC;
        this.headers = headers != null ? headers : new HashMap<>();
        this.payload = payload;
    }
}
