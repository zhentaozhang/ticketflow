package com.ticketflow.loadbalance;

import com.ticketflow.constant.Constant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.RequestData;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GrayReactiveLoadBalancerTest {

    private GrayReactiveLoadBalancer balancer;
    @SuppressWarnings("rawtypes")
    private ObjectProvider<ServiceInstanceListSupplier> provider;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ServiceInstanceListSupplier> mockProvider = mock(ObjectProvider.class);
        provider = mockProvider;
        balancer = new GrayReactiveLoadBalancer(provider, "ticketflow-order-service");
    }

    private ServiceInstance instance(String id, String gray) {
        ServiceInstance instance = mock(ServiceInstance.class);
        when(instance.getInstanceId()).thenReturn(id);
        when(instance.getServiceId()).thenReturn("ticketflow-order-service");
        when(instance.getMetadata()).thenReturn(gray == null ? Map.of() : Map.of(Constant.GRAY_PARAMETER, gray));
        return instance;
    }

    @SuppressWarnings("unchecked")
    private Request<RequestDataContext> requestWithGrayHeader(String grayValue) {
        HttpHeaders headers = new HttpHeaders();
        if (grayValue != null) {
            headers.add(Constant.GRAY_PARAMETER, grayValue);
        }
        RequestData requestData = mock(RequestData.class);
        when(requestData.getHeaders()).thenReturn(headers);

        RequestDataContext context = mock(RequestDataContext.class);
        when(context.getClientRequest()).thenReturn(requestData);

        Request<RequestDataContext> request = mock(Request.class);
        when(request.getContext()).thenReturn(context);
        return request;
    }

    @SuppressWarnings("unchecked")
    private void stubInstances(List<ServiceInstance> instances) {
        ServiceInstanceListSupplier supplier = mock(ServiceInstanceListSupplier.class);
        when(provider.getIfAvailable(any())).thenReturn(supplier);
        when(supplier.get(any(Request.class))).thenReturn(Flux.just(instances));
    }

    @Test
    void choose_grayRequestOnlySelectsGrayInstance() {
        ServiceInstance gray = instance("gray-1", Constant.GRAY_FLAG_TRUE);
        ServiceInstance normal = instance("normal-1", null);
        stubInstances(List.of(gray, normal));

        Response<ServiceInstance> response = balancer.choose(requestWithGrayHeader(Constant.GRAY_FLAG_TRUE)).block();

        assertNotNull(response);
        assertTrue(response.hasServer());
        assertEquals("gray-1", response.getServer().getInstanceId());
    }

    @Test
    void choose_noGrayHeaderOnlySelectsNormalInstance() {
        ServiceInstance gray = instance("gray-1", Constant.GRAY_FLAG_TRUE);
        ServiceInstance normal = instance("normal-1", null);
        stubInstances(List.of(gray, normal));

        Response<ServiceInstance> response = balancer.choose(requestWithGrayHeader(null)).block();

        assertNotNull(response);
        assertTrue(response.hasServer());
        assertEquals("normal-1", response.getServer().getInstanceId());
    }

    @Test
    void choose_grayRequestNoGrayInstance_fallsBackToAll() {
        ServiceInstance normal1 = instance("normal-1", null);
        ServiceInstance normal2 = instance("normal-2", null);
        stubInstances(List.of(normal1, normal2));

        Response<ServiceInstance> response = balancer.choose(requestWithGrayHeader(Constant.GRAY_FLAG_TRUE)).block();

        assertNotNull(response);
        assertTrue(response.hasServer());
        // 过滤结果为空时降级到全部实例，避免灰度无实例导致流量中断
        assertFalse(Constant.GRAY_FLAG_TRUE.equalsIgnoreCase(response.getServer().getMetadata().get(Constant.GRAY_PARAMETER)));
    }

    @Test
    void choose_nonGrayRequestNoNormalInstance_fallsBackToAll() {
        ServiceInstance gray1 = instance("gray-1", Constant.GRAY_FLAG_TRUE);
        ServiceInstance gray2 = instance("gray-2", Constant.GRAY_FLAG_TRUE);
        stubInstances(List.of(gray1, gray2));

        Response<ServiceInstance> response = balancer.choose(requestWithGrayHeader(null)).block();

        assertNotNull(response);
        assertTrue(response.hasServer());
        assertTrue(Constant.GRAY_FLAG_TRUE.equalsIgnoreCase(response.getServer().getMetadata().get(Constant.GRAY_PARAMETER)));
    }

    @Test
    void choose_emptyInstanceList_returnsEmptyResponse() {
        stubInstances(List.of());

        Response<ServiceInstance> response = balancer.choose(requestWithGrayHeader(Constant.GRAY_FLAG_TRUE)).block();

        assertNotNull(response);
        assertFalse(response.hasServer());
    }

    @Test
    void choose_singleNormalInstanceWithGrayRequest_fallsBackToIt() {
        ServiceInstance normal = instance("normal-1", null);
        stubInstances(List.of(normal));

        Response<ServiceInstance> response = balancer.choose(requestWithGrayHeader(Constant.GRAY_FLAG_TRUE)).block();

        assertNotNull(response);
        assertTrue(response.hasServer());
        assertEquals("normal-1", response.getServer().getInstanceId());
    }

    @Test
    void choose_unknownGrayValue_treatedAsNonGrayRequest() {
        ServiceInstance gray = instance("gray-1", Constant.GRAY_FLAG_TRUE);
        ServiceInstance normal = instance("normal-1", null);
        stubInstances(List.of(gray, normal));

        Response<ServiceInstance> response = balancer.choose(requestWithGrayHeader("yes")).block();

        assertNotNull(response);
        assertTrue(response.hasServer());
        assertEquals("normal-1", response.getServer().getInstanceId());
    }
}
