package com.ticketflow.loadbalance;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/**
 * 网关灰度负载均衡激活配置：
 * <p>
 * 为 ticketflow-user-service 注册请求感知的灰度负载均衡器 {@link GrayReactiveLoadBalancer}
 * （从 RequestDataContext 读取 gray 请求头，按实例元数据 gray 过滤，无灰度实例时降级全部实例）。
 * <p>
 * 实现原理：child context 中官方 LoadBalancerClientConfiguration（defaultConfigType，注册在
 * default.* 之后）的 supplier 会覆盖框架 EnhanceLoadBalancerClientConfiguration 的同名 supplier，
 * 因此网关侧使用官方 withCaching() 链（避免框架 ServerGrayFilter 按网关自身配置误过滤实例）；
 * 本配置的负载均衡器 bean 与官方同名定义不同（grayReactorServiceInstanceLoadBalancer），
 * 并以 @Primary 保证多候选时被选中，不受注册顺序影响。
 */
@LoadBalancerClient(name = "ticketflow-user-service",
        configuration = GrayLoadBalancerConfiguration.GrayLoadBalancerSupportConfiguration.class)
public class GrayLoadBalancerConfiguration {

    @Configuration(proxyBeanMethods = false)
    public static class GrayLoadBalancerSupportConfiguration {

        @Bean
        @Primary
        public ReactorLoadBalancer<ServiceInstance> grayReactorServiceInstanceLoadBalancer(
                Environment environment, LoadBalancerClientFactory loadBalancerClientFactory) {
            String name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
            ObjectProvider<ServiceInstanceListSupplier> lazyProvider =
                    loadBalancerClientFactory.getLazyProvider(name, ServiceInstanceListSupplier.class);
            return new GrayReactiveLoadBalancer(lazyProvider, name);
        }
    }
}
