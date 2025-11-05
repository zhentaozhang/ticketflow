package com.ticketflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.concurrent.TimeUnit;

import static com.ticketflow.config.DelayQueueProperties.PREFIX;

/**
 * 延迟队列配置属性（Redisson 实现）。
 * 核心参数：批量拉取数量（pullBatchSize）、
 * 消费线程数（consumeThreadMin/Max）
 */
@Data
@ConfigurationProperties(prefix = PREFIX)
public class DelayQueueProperties {

    public static final String PREFIX = "delay.queue";
    
    /**
     * 从队列拉取数据的线程池中的核心线程数量，如果业务过慢可调大
     * */
    private Integer corePoolSize = 4;
    /**
     * 从队列拉取数据的线程池中的最大线程数量，如果业务过慢可调大
     * */
    private Integer maximumPoolSize = 4;
    
    /**
     * 从队列拉取数据的线程池中的最大线程回收时间
     * */
    private long keepAliveTime = 30;
    /**
     * 从队列拉取数据的线程池中的最大线程回收时间的时间单位
     * */
    private TimeUnit unit = TimeUnit.SECONDS;
    /**
     * 从队列拉取数据的线程池中的队列数量，如果业务过慢可调大
     * */
    private Integer workQueueSize = 256;
    
    /**
     * 延时队列的隔离分区数。
     * 将同一个 topic 拆成 N 个物理分区（topic-0 ~ topic-N），
     * 缓解 Redis 单键热点，提升延迟队列吞吐量。
     *
     * 生产者和消费者使用相同的 topic 拼接规则（topic + "-" + index），
     * isolationRegionCount 必须一致，否则分区不匹配导致消息无法消费。
     *
     * 调大可提升吞吐，但会增加 Redis 的 CPU 开销（每个分区有独立的延时排序）。
     * */
    private Integer isolationRegionCount = 5;
}
