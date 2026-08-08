package com.ticketflow.filter.impl;

import com.alibaba.cloud.nacos.NacosServiceInstance;
import com.ticketflow.context.ContextHandler;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.threadlocal.BaseParameterHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ticketflow.constant.Constant.GRAY_FLAG_FALSE;
import static com.ticketflow.constant.Constant.GRAY_FLAG_TRUE;
import static com.ticketflow.constant.Constant.GRAY_PARAMETER;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServerGrayFilterTest {

    @Mock
    private ContextHandler contextHandler;

    private ServerGrayFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ServerGrayFilter(contextHandler);
    }

    @AfterEach
    void tearDown() {
        BaseParameterHolder.removeParameterMap();
    }

    @Test
    void grayRequestShouldMatchGrayInstance() {
        setServerGray(filter, GRAY_FLAG_FALSE);
        when(contextHandler.getValueFromHeader(GRAY_PARAMETER)).thenReturn(GRAY_FLAG_TRUE);

        List<ServiceInstance> servers = new ArrayList<>();
        NacosServiceInstance server = grayInstance(instance("1.1.1.1", gray("true")));

        assertTrue(filter.doFilter(servers, server));
    }

    @Test
    void noHeaderShouldFallBackToThreadLocal() {
        setServerGray(filter, GRAY_FLAG_FALSE);
        when(contextHandler.getValueFromHeader(GRAY_PARAMETER)).thenReturn("");
        BaseParameterHolder.setParameter(GRAY_PARAMETER, GRAY_FLAG_TRUE);

        List<ServiceInstance> servers = new ArrayList<>();
        NacosServiceInstance server = grayInstance(instance("1.1.1.1", gray("true")));

        assertTrue(filter.doFilter(servers, server));
    }

    @Test
    void emptyHeaderAndThreadLocalShouldFallBackToServerGrayConfig() {
        setServerGray(filter, GRAY_FLAG_TRUE);
        when(contextHandler.getValueFromHeader(GRAY_PARAMETER)).thenReturn(null);
        BaseParameterHolder.removeParameterMap();

        List<ServiceInstance> servers = new ArrayList<>();
        NacosServiceInstance server = grayInstance(instance("1.1.1.1", gray("true")));

        assertTrue(filter.doFilter(servers, server));
    }

    @Test
    void missingInstanceMetadataShouldDefaultToNonGray() {
        setServerGray(filter, GRAY_FLAG_FALSE);
        when(contextHandler.getValueFromHeader(GRAY_PARAMETER)).thenReturn(GRAY_FLAG_TRUE);

        List<ServiceInstance> servers = new ArrayList<>();
        servers.add(instance("2.2.2.2", gray("true")));
        NacosServiceInstance server = grayInstance(instance("1.1.1.1", null));

        assertFalse(filter.doFilter(servers, server));
    }

    @Test
    void grayValueShouldBeNormalizedCaseInsensitively() {
        setServerGray(filter, GRAY_FLAG_FALSE);
        when(contextHandler.getValueFromHeader(GRAY_PARAMETER)).thenReturn("TRUE");

        List<ServiceInstance> servers = new ArrayList<>();
        NacosServiceInstance server = grayInstance(instance("1.1.1.1", gray("true")));

        assertTrue(filter.doFilter(servers, server));
    }

    @Test
    void unknownGrayValueShouldBeTreatedAsNonGray() {
        setServerGray(filter, GRAY_FLAG_FALSE);
        when(contextHandler.getValueFromHeader(GRAY_PARAMETER)).thenReturn("yes");

        List<ServiceInstance> servers = new ArrayList<>();
        NacosServiceInstance server = grayInstance(instance("1.1.1.1", gray("false")));

        assertTrue(filter.doFilter(servers, server));
    }

    @Test
    void grayRequestWithEmptyServerListShouldThrow() {
        setServerGray(filter, GRAY_FLAG_FALSE);
        when(contextHandler.getValueFromHeader(GRAY_PARAMETER)).thenReturn(GRAY_FLAG_TRUE);

        NacosServiceInstance server = grayInstance(instance("1.1.1.1", gray("false")));

        assertThrows(TicketFlowFrameException.class, () -> filter.doFilter(new ArrayList<>(), server));
    }

    @Test
    void grayRequestWithoutAnyGrayInstanceShouldFallBackToAccept() {
        setServerGray(filter, GRAY_FLAG_FALSE);
        when(contextHandler.getValueFromHeader(GRAY_PARAMETER)).thenReturn(GRAY_FLAG_TRUE);

        List<ServiceInstance> servers = new ArrayList<>();
        servers.add(instance("1.1.1.1", gray("false")));
        servers.add(instance("2.2.2.2", gray("false")));
        NacosServiceInstance server = grayInstance(instance("1.1.1.1", gray("false")));

        assertTrue(filter.doFilter(servers, server));
    }

    @Test
    void grayRequestShouldBeFilteredWhenOtherGrayInstancesExist() {
        setServerGray(filter, GRAY_FLAG_FALSE);
        when(contextHandler.getValueFromHeader(GRAY_PARAMETER)).thenReturn(GRAY_FLAG_TRUE);

        List<ServiceInstance> servers = new ArrayList<>();
        servers.add(instance("1.1.1.1", gray("false")));
        servers.add(instance("2.2.2.2", gray("true")));
        NacosServiceInstance server = grayInstance(instance("1.1.1.1", gray("false")));

        assertFalse(filter.doFilter(servers, server));
    }

    private NacosServiceInstance grayInstance(NacosServiceInstance instance) {
        instance.setServiceId("ticketflow-user-service");
        return instance;
    }

    private NacosServiceInstance instance(String host, Map<String, String> metadata) {
        NacosServiceInstance instance = new NacosServiceInstance();
        instance.setHost(host);
        instance.setPort(8080);
        instance.setMetadata(metadata);
        return instance;
    }

    private Map<String, String> gray(String value) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put(GRAY_PARAMETER, value);
        return metadata;
    }

    private void setServerGray(ServerGrayFilter filter, String value) {
        ReflectionTestUtils.setField(filter, "serverGray", value);
    }
}
