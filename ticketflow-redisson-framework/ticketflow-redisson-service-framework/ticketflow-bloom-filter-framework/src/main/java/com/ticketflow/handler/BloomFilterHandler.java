package com.ticketflow.handler;


import com.ticketflow.config.BloomFilterProperties;
import com.ticketflow.core.SpringUtil;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;


/**
 * Bloom 过滤器封装，用于防止缓存穿透。
 * <p>
 * 使用场景（order-service 创建订单时）：
 * 1. 外层：BloomFilterHandler 快速判断 programId 是否可能有效
 * 2. 中间层：本地 Caffeine 缓存兜底
 * 3. 内层：分布式 Redis 缓存 + RedisKeyBuild 缓存
 * <p>
 * Redisson RBloomFilter 基于 Redisson 的 BitArray 实现，tryInit 不可变——
 * 初始化后容量（expectedInsertions）和误报率（falseProbability）不可修改。
 * 实际元素 count 可动态增减（add / contains）
 */
public class BloomFilterHandler {

    private final RBloomFilter<String> cachePenetrationBloomFilter;

    public BloomFilterHandler(RedissonClient redissonClient, BloomFilterProperties bloomFilterProperties) {
        // key = env前缀 + 配置名（如 "ticketflow-bloom_filter_program"），实现环境隔离
        RBloomFilter<String> cachePenetrationBloomFilter = redissonClient.getBloomFilter(
                SpringUtil.getPrefixDistinctionName() + "-" + bloomFilterProperties.getName());
        // tryInit 仅在 BloomFilter 不存在时初始化，已存在则跳过（不可变更容量/误报率）
        cachePenetrationBloomFilter.tryInit(bloomFilterProperties.getExpectedInsertions(),
                bloomFilterProperties.getFalseProbability());
        this.cachePenetrationBloomFilter = cachePenetrationBloomFilter;
    }

    public boolean add(String data) {
        return cachePenetrationBloomFilter.add(data);
    }

    public boolean contains(String data) {
        return cachePenetrationBloomFilter.contains(data);
    }

    public long getExpectedInsertions() {
        return cachePenetrationBloomFilter.getExpectedInsertions();
    }

    public double getFalseProbability() {
        return cachePenetrationBloomFilter.getFalseProbability();
    }

    public long getSize() {
        return cachePenetrationBloomFilter.getSize();
    }

    public int getHashIterations() {
        return cachePenetrationBloomFilter.getHashIterations();
    }

    public long count() {
        return cachePenetrationBloomFilter.count();
    }
}
