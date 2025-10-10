package com.ticketflow.filter;


import org.springframework.cloud.client.ServiceInstance;
import org.springframework.core.Ordered;

import java.util.Iterator;
import java.util.List;

/**
 * 服务实例过滤器基类（模板方法模式）。
 * filter() 遍历服务列表，调用子类 doFilter() 判断每个实例是否可用，
 * 不可用的实例从列表中移除。
 *
 * ServerGrayFilter 为其实现——根据灰度标识过滤服务实例。
 * 结合 Nacos 元数据实现灰度环境的路由隔离。
 */
public abstract class AbstractServerFilter implements Ordered {
    
    public void filter(List<? extends ServiceInstance> servers) {
        Iterator<? extends ServiceInstance> iterator = servers.iterator();
        while (iterator.hasNext()) {
            ServiceInstance server = iterator.next();
            boolean enabled = doFilter(servers, server);
            if (!enabled) {
                iterator.remove();
            }
        }
    }

    /**
     * 执行真正地过滤行为
     * @param servers 被调用的所有服务列表
     * @param server 当前被调用的服务
     * @return 过滤的结果
     * */
    public abstract boolean doFilter(List<? extends ServiceInstance> servers, ServiceInstance server);
}