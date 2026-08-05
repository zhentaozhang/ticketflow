package com.ticketflow.filterbalance;


import com.ticketflow.balance.FilterLoadBalance;
import com.ticketflow.filter.AbstractServerFilter;
import lombok.AllArgsConstructor;
import org.springframework.cloud.client.ServiceInstance;

import java.util.List;

/**
 * 默认负载均衡过滤器实现。
 * 将多个 AbstractServerFilter（如 ServerGrayFilter）按注入顺序编排为过滤链，
 * 每个过滤器对服务实例列表进行筛选，不满足条件的实例从列表中移除。
 *
 * 责任链模式：多个过滤条件可叠加——先经过灰度过滤，再经过其他安全/可用性过滤
 */
@AllArgsConstructor
public class DefaultFilterLoadBalance implements FilterLoadBalance {

    protected final List<AbstractServerFilter> strategyFilterList;

    @Override
    public void selectServer(List<ServiceInstance> servers) {
        for (AbstractServerFilter strategyEnabledFilter : strategyFilterList) {
            strategyEnabledFilter.filter(servers);
        }
    }
}