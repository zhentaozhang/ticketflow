package com.ticketflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bloom 过滤器配置属性（bloom-filter.*）。
 *
 * name:         过滤器名称（Redis key）
 * expectedInsertions: 预估插入量（影响位数组大小）
 * falseProbability:   误判率（默认 0.03）
 */
@Data
@ConfigurationProperties(prefix = BloomFilterProperties.PREFIX)
public class BloomFilterProperties {

    public static final String PREFIX = "bloom-filter";
    
    private String name;
    
    private Long expectedInsertions = 20000L;
    
    private Double falseProbability = 0.01D;
}
