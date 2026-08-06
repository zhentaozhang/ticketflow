package com.ticketflow.loadbalance;

import cn.hutool.core.collection.CollectionUtil;
import com.ticketflow.constant.Constant;
import com.ticketflow.util.StringUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
import org.springframework.cloud.client.loadbalancer.EmptyResponse;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.loadbalancer.core.NoopServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.cloud.loadbalancer.core.SelectedInstanceCallback;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 请求感知的灰度负载均衡器（网关路由转发专用）：
 * <p>
 * 从请求上下文（RequestDataContext）中读取 gray 请求头，按实例元数据 gray 标记过滤候选实例，
 * 不存在 ThreadLocal / 跨线程传递问题。过滤结果为空时降级为全部实例（避免无灰度实例时流量中断）。
 */
public class GrayReactiveLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    private static final Log log = LogFactory.getLog(GrayReactiveLoadBalancer.class);

    private final AtomicInteger position = new AtomicInteger(new Random().nextInt(1000));

    private final String serviceId;

    private final ObjectProvider<ServiceInstanceListSupplier> serviceInstanceListSupplierProvider;

    public GrayReactiveLoadBalancer(ObjectProvider<ServiceInstanceListSupplier> serviceInstanceListSupplierProvider, String serviceId) {
        this.serviceId = serviceId;
        this.serviceInstanceListSupplierProvider = serviceInstanceListSupplierProvider;
    }

    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        ServiceInstanceListSupplier supplier = serviceInstanceListSupplierProvider.getIfAvailable(NoopServiceInstanceListSupplier::new);
        return supplier.get(request).next()
                .map(serviceInstances -> processInstanceResponse(supplier, filterByGray(serviceInstances, getGrayFromRequest(request))));
    }

    private String getGrayFromRequest(Request request) {
        if (request == null || !(request.getContext() instanceof RequestDataContext requestDataContext)) {
            return null;
        }
        return Optional.ofNullable(requestDataContext.getClientRequest())
                .map(clientRequest -> clientRequest.getHeaders().getFirst(Constant.GRAY_PARAMETER))
                .filter(StringUtil::isNotEmpty)
                .orElse(null);
    }

    private List<ServiceInstance> filterByGray(List<ServiceInstance> instances, String gray) {
        if (CollectionUtil.isEmpty(instances)) {
            return instances;
        }
        boolean grayRequest = Constant.GRAY_FLAG_TRUE.equalsIgnoreCase(gray);
        List<ServiceInstance> matched = new ArrayList<>(instances.size());
        for (ServiceInstance instance : instances) {
            String instanceGray = Optional.ofNullable(instance.getMetadata())
                    .map(metadata -> metadata.get(Constant.GRAY_PARAMETER))
                    .filter(StringUtil::isNotEmpty)
                    .orElse(Constant.GRAY_FLAG_FALSE);
            boolean matchedGray = grayRequest
                    ? Constant.GRAY_FLAG_TRUE.equalsIgnoreCase(instanceGray)
                    : !Constant.GRAY_FLAG_TRUE.equalsIgnoreCase(instanceGray);
            if (matchedGray) {
                matched.add(instance);
            }
        }
        if (CollectionUtil.isEmpty(matched)) {
            if (log.isWarnEnabled()) {
                log.warn("gray filter result is empty for service: " + serviceId + ", grayRequest=" + grayRequest
                        + ", fallback to all instances");
            }
            return new ArrayList<>(instances);
        }
        return matched;
    }

    private Response<ServiceInstance> processInstanceResponse(ServiceInstanceListSupplier supplier, List<ServiceInstance> serviceInstances) {
        Response<ServiceInstance> serviceInstanceResponse = getInstanceResponse(serviceInstances);
        if (supplier instanceof SelectedInstanceCallback && serviceInstanceResponse.hasServer()) {
            ((SelectedInstanceCallback) supplier).selectedServiceInstance(serviceInstanceResponse.getServer());
        }
        return serviceInstanceResponse;
    }

    private Response<ServiceInstance> getInstanceResponse(List<ServiceInstance> instances) {
        if (instances.isEmpty()) {
            if (log.isWarnEnabled()) {
                log.warn("No servers available for service: " + serviceId);
            }
            return new EmptyResponse();
        }
        if (instances.size() == 1) {
            return new DefaultResponse(instances.get(0));
        }
        int pos = this.position.incrementAndGet() & Integer.MAX_VALUE;
        return new DefaultResponse(instances.get(pos % instances.size()));
    }
}
