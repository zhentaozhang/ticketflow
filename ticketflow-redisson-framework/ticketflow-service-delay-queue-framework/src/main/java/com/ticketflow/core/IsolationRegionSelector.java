package com.ticketflow.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 延迟队列热点分区选择器。
 *
 * 同一个 topic 的消息集中在一个 Redis 键上，单分区会成为性能瓶颈。
 * 将 topic 拆成 N 个物理分区（topic-0 ~ topic-N），生产者轮询写入，
 * 消费者每个分区独立监听，相当于给 Redis 延迟队列做了水平拆分。
 *
 * getIndex() 使用 synchronized 保证线程安全的自增轮询。
 **/
public class IsolationRegionSelector {

	private final AtomicInteger count = new AtomicInteger(0);

	private final Integer thresholdValue;

	public IsolationRegionSelector(Integer thresholdValue) {
		this.thresholdValue = thresholdValue;
	}

	private int reset() {
		count.set(0);
		return count.get();
	}
	
	public synchronized int getIndex() {
		int cur = count.get();
		if (cur >= thresholdValue) {
			cur = reset();
		} else {
			count.incrementAndGet();
		}
		return cur;
	}
}
