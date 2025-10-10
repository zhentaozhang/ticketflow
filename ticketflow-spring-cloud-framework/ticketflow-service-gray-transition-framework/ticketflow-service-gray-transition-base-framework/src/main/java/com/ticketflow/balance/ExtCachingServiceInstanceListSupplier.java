package com.ticketflow.balance;

import org.springframework.cache.CacheManager;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.CachingServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 增强的缓存服务实例供应器。
 * 继承 CachingServiceInstanceListSupplier，在 get() 获取实例列表后
 * 应用 FilterLoadBalance 过滤链，实现灰度路由的服务端筛选。
 *
 * Spring Cloud LoadBalancer 的扩展点——通过自定义 ServiceInstanceListSupplier
 * 替代默认实现实现灰度发布路由
 */
public class ExtCachingServiceInstanceListSupplier extends CachingServiceInstanceListSupplier {
    
    private final FilterLoadBalance filterLoadBalance;
    
    public ExtCachingServiceInstanceListSupplier(ServiceInstanceListSupplier delegate, 
                                                 CacheManager cacheManager,
                                                 FilterLoadBalance filterLoadBalance) {
        super(delegate, cacheManager);
        this.filterLoadBalance = filterLoadBalance;
    }
    
    @Override
    public Flux<List<ServiceInstance>> get() {
        Flux<List<ServiceInstance>> listFlux = super.get();
        listFlux = listFlux.map(serviceInstances -> {
            List<ServiceInstance> allServers = new ArrayList<>();
            Optional.ofNullable(serviceInstances).ifPresent(allServers::addAll);
            filterLoadBalance.selectServer(allServers);
            return allServers;
        });
        return listFlux;
    }
}
