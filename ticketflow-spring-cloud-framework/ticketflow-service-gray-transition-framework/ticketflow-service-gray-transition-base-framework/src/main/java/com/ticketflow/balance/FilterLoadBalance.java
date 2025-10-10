package com.ticketflow.balance;

import org.springframework.cloud.client.ServiceInstance;

import java.util.List;

/**
 * 负载均衡服务过滤接口（策略模式顶层接口）。
 * 通过 selectServer() 对服务实例列表进行过滤，移除不满足当前条件的实例。
 *
 * 实现：DefaultFilterLoadBalance 组合多个 AbstractServerFilter，
 *       按 Ordered 排序依次执行过滤
 */
public interface FilterLoadBalance {
    
    /**
     * 服务过滤操作
     * @param servers 服务列表
     * */
    void selectServer(List<ServiceInstance> servers);
}
