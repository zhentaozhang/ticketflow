package com.ticketflow.balance.config;

import com.ticketflow.context.ContextHandler;
import com.ticketflow.enhance.config.EnhanceLoadBalancerClientConfiguration;
import com.ticketflow.enhance.config.EnhanceLoadBalancerClientConfiguration.BlockingSupportConfiguration;
import com.ticketflow.enhance.config.EnhanceLoadBalancerClientConfiguration.ReactiveSupportConfiguration;
import com.ticketflow.filter.AbstractServerFilter;
import com.ticketflow.filter.impl.ServerGrayFilter;
import com.ticketflow.filterbalance.DefaultFilterLoadBalance;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * 灰度 LoadBalancer 自动配置。
 * 注册自定义 ServiceInstanceListSupplier（EnhanceServiceInstanceListSupplierBuilder），
 * 替换默认的轮询/随机负载均衡策略
 */
@LoadBalancerClients(defaultConfiguration = {EnhanceLoadBalancerClientConfiguration.class, ReactiveSupportConfiguration.class, BlockingSupportConfiguration.class})
public class GrayLoadBalanceAutoConfiguration {
    
    @Bean
    public DefaultFilterLoadBalance defaultFilterLoadBalance(List<AbstractServerFilter> strategyEnabledFilterList){
        return new DefaultFilterLoadBalance(strategyEnabledFilterList);
    }
    
    @Bean
    public AbstractServerFilter serverGrayFilter(ContextHandler contextHandler) {
        return new ServerGrayFilter(contextHandler);
    }
}
