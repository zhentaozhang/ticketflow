package com.ticketflow.loadbalance;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClientsProperties;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClientSpecification;
import org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import java.util.Collections;
import java.util.List;

/**
 * 网关灰度负载均衡激活配置：
 * <p>
 * 用 {@link GrayLoadBalancerClientFactory}（@Primary）替换官方 LoadBalancerClientFactory，
 * 使所有服务的 child context 注册请求感知的灰度负载均衡器 {@link GrayReactiveLoadBalancer}
 * （从 RequestDataContext 读取 gray 请求头，按实例元数据 gray 过滤，无灰度实例时降级全部实例）。
 * <p>
 * 注意：child context 中框架 {@code EnhanceLoadBalancerClientConfiguration}（default.* 分支）
 * 的同名 supplier/LB bean 会被本配置覆盖，网关侧缓存链由框架 ExtCaching 变为官方
 * withCaching()（功能等价）；服务端（业务服务）的框架灰度链不受影响。
 */
@Configuration(proxyBeanMethods = false)
public class GrayLoadBalancerConfiguration {

    @Bean
    @Primary
    public LoadBalancerClientFactory grayLoadBalancerClientFactory(LoadBalancerClientsProperties properties,
            ObjectProvider<List<LoadBalancerClientSpecification>> configurations) {
        GrayLoadBalancerClientFactory clientFactory = new GrayLoadBalancerClientFactory(properties);
        clientFactory.setConfigurations(configurations.getIfAvailable(Collections::emptyList));
        return clientFactory;
    }

    @Configuration(proxyBeanMethods = false)
    public static class GrayLoadBalancerSupportConfiguration {

        @Bean
        public ReactorLoadBalancer<ServiceInstance> reactorServiceInstanceLoadBalancer(
                Environment environment, LoadBalancerClientFactory loadBalancerClientFactory) {
            String name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
            ObjectProvider<ServiceInstanceListSupplier> lazyProvider =
                    loadBalancerClientFactory.getLazyProvider(name, ServiceInstanceListSupplier.class);
            return new GrayReactiveLoadBalancer(lazyProvider, name);
        }

        @Configuration(proxyBeanMethods = false)
        public static class ReactiveSupportConfiguration {

            @Bean
            @ConditionalOnBean(ReactiveDiscoveryClient.class)
            public ServiceInstanceListSupplier discoveryClientServiceInstanceListSupplier(
                    ConfigurableApplicationContext context) {
                return ServiceInstanceListSupplier.builder().withDiscoveryClient().withCaching().build(context);
            }
        }

        @Configuration(proxyBeanMethods = false)
        public static class BlockingSupportConfiguration {

            @Bean
            @ConditionalOnBean(DiscoveryClient.class)
            public ServiceInstanceListSupplier discoveryClientServiceInstanceListSupplier(
                    ConfigurableApplicationContext context) {
                return ServiceInstanceListSupplier.builder().withBlockingDiscoveryClient().withCaching().build(context);
            }
        }
    }
}
