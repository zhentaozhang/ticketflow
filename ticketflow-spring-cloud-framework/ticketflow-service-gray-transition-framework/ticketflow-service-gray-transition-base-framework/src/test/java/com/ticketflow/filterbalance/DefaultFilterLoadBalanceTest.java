package com.ticketflow.filterbalance;

import com.alibaba.cloud.nacos.NacosServiceInstance;
import com.ticketflow.filter.AbstractServerFilter;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.ServiceInstance;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultFilterLoadBalanceTest {

    @Test
    void filterShouldRemoveInstancesRejectedByDoFilter() {
        NacosServiceInstance keep = instance("1.1.1.1");
        NacosServiceInstance drop = instance("2.2.2.2");
        List<ServiceInstance> servers = new ArrayList<>();
        servers.add(keep);
        servers.add(drop);

        AbstractServerFilter filter = new AbstractServerFilter() {
            @Override
            public boolean doFilter(List<? extends ServiceInstance> s, ServiceInstance server) {
                return !"2.2.2.2".equals(server.getHost());
            }

            @Override
            public int getOrder() {
                return 0;
            }
        };

        filter.filter(servers);

        assertEquals(1, servers.size());
        assertTrue(servers.contains(keep));
    }

    @Test
    void selectServerShouldApplyAllFiltersInOrder() {
        List<AbstractServerFilter> filters = new ArrayList<>();
        List<String> executionLog = new ArrayList<>();
        filters.add(new LoggingFilter("first", executionLog));
        filters.add(new LoggingFilter("second", executionLog));

        NacosServiceInstance keep = instance("1.1.1.1");
        List<ServiceInstance> servers = new ArrayList<>();
        servers.add(keep);

        DefaultFilterLoadBalance loadBalance = new DefaultFilterLoadBalance(filters);
        loadBalance.selectServer(servers);

        assertEquals(2, executionLog.size());
        assertEquals("first", executionLog.get(0));
        assertEquals("second", executionLog.get(1));
    }

    @Test
    void selectServerShouldApplyFiltersSequentiallyToRemainingInstances() {
        List<AbstractServerFilter> filters = new ArrayList<>();
        filters.add(new HostFilter("1.1.1.1", false));
        filters.add(new HostFilter("2.2.2.2", false));

        NacosServiceInstance first = instance("1.1.1.1");
        NacosServiceInstance second = instance("2.2.2.2");
        List<ServiceInstance> servers = new ArrayList<>();
        servers.add(first);
        servers.add(second);

        DefaultFilterLoadBalance loadBalance = new DefaultFilterLoadBalance(filters);
        loadBalance.selectServer(servers);

        assertTrue(servers.isEmpty());
    }

    static class LoggingFilter extends AbstractServerFilter {
        private final String name;
        private final List<String> log;

        LoggingFilter(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public boolean doFilter(List<? extends ServiceInstance> servers, ServiceInstance server) {
            log.add(name);
            return true;
        }

        @Override
        public int getOrder() {
            return 0;
        }
    }

    static class HostFilter extends AbstractServerFilter {
        private final String dropHost;
        private final boolean keep;

        HostFilter(String dropHost, boolean keep) {
            this.dropHost = dropHost;
            this.keep = keep;
        }

        @Override
        public boolean doFilter(List<? extends ServiceInstance> servers, ServiceInstance server) {
            if (dropHost.equals(server.getHost())) {
                return keep;
            }
            return true;
        }

        @Override
        public int getOrder() {
            return 0;
        }
    }

    private NacosServiceInstance instance(String host) {
        NacosServiceInstance instance = new NacosServiceInstance();
        instance.setHost(host);
        instance.setPort(8080);
        return instance;
    }
}
