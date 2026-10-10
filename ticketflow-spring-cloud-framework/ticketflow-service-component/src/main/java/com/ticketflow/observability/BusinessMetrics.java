package com.ticketflow.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

/**
 * 业务指标空安全埋点工具。
 *
 * <p>设计约束（企业实践）：可观测性代码不允许影响交易主流程 —— 任何埋点失败
 * （registry 未注入、单元测试 mock 未 stub、指标系统瞬时异常）都被吞掉并降级为
 * debug 日志。业务代码应始终通过本工具埋点，而不是直接操作 {@link MeterRegistry}。
 */
@Slf4j
public final class BusinessMetrics {

    private BusinessMetrics() {
    }

    /**
     * 计数 +1（等价 {@link MeterRegistry#counter(String, String...)} 后 increment）。
     *
     * @param registry   Micrometer registry（允许为 null，如单元测试未注入）
     * @param metricName 指标名（见 {@link Metrics} 词典）
     * @param tags       tag 键值对（k1, v1, k2, v2, ...）
     */
    public static void increment(MeterRegistry registry, String metricName, String... tags) {
        try {
            if (registry == null) {
                return;
            }
            Counter counter = registry.counter(metricName, tags);
            if (counter != null) {
                counter.increment();
            }
        } catch (RuntimeException ex) {
            log.debug("business metric increment failed, metric={}", metricName, ex);
        }
    }

    /**
     * 记录耗时分布（Timer，单位秒），等价
     * {@code Timer.builder(name).tags(tags).register(registry)} 后 record。
     */
    public static void recordSeconds(MeterRegistry registry, String metricName, double seconds, String... tags) {
        try {
            if (registry == null) {
                return;
            }
            Timer timer = registry.timer(metricName, tags);
            if (timer != null) {
                timer.record((long) (seconds * 1_000_000_000L), TimeUnit.NANOSECONDS);
            }
        } catch (RuntimeException ex) {
            log.debug("business metric record failed, metric={}", metricName, ex);
        }
    }
}
