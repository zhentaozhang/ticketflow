package com.ticketflow.loadbalance;

import com.ticketflow.loadbalance.GrayLoadBalancerConfiguration.GrayLoadBalancerSupportConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClientsProperties;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClientSpecification;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.AnnotationConfigRegistry;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.util.Assert;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自定义 LoadBalancerClientFactory：
 * <p>
 * 覆盖 registerBeans，使每个服务的 child context 不再注册官方
 * {@code LoadBalancerClientConfiguration}（其 RoundRobinLoadBalancer 会覆盖我们的灰度负载均衡器），
 * 改为注册 {@link GrayLoadBalancerSupportConfiguration}（请求感知的灰度负载均衡器 + 供应商链）。
 * <p>
 * registerBeans 实现复制自 spring-cloud-loadbalancer 4.1.3 的
 * NamedContextFactory#registerBeans，升级该依赖时需同步验证。
 */
public class GrayLoadBalancerClientFactory extends LoadBalancerClientFactory {

    private Map<String, LoadBalancerClientSpecification> configurationsCopy = new HashMap<>();

    public GrayLoadBalancerClientFactory(LoadBalancerClientsProperties properties) {
        super(properties);
    }

    @Override
    public void setConfigurations(List<LoadBalancerClientSpecification> configurations) {
        super.setConfigurations(configurations);
        this.configurationsCopy = new HashMap<>();
        for (LoadBalancerClientSpecification client : configurations) {
            this.configurationsCopy.put(client.getName(), client);
        }
    }

    @Override
    public void registerBeans(String name, GenericApplicationContext context) {
        Assert.isInstanceOf(AnnotationConfigRegistry.class, context);
        AnnotationConfigRegistry registry = (AnnotationConfigRegistry) context;
        if (configurationsCopy.containsKey(name)) {
            for (Class<?> configuration : configurationsCopy.get(name).getConfiguration()) {
                registry.register(configuration);
            }
        }
        for (Map.Entry<String, LoadBalancerClientSpecification> entry : configurationsCopy.entrySet()) {
            if (entry.getKey().startsWith("default.")) {
                for (Class<?> configuration : entry.getValue().getConfiguration()) {
                    registry.register(configuration);
                }
            }
        }
        registry.register(PropertyPlaceholderAutoConfiguration.class, GrayLoadBalancerSupportConfiguration.class);
    }
}
