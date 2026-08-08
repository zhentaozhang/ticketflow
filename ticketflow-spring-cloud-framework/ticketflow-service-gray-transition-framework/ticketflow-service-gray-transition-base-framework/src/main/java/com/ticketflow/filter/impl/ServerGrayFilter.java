package com.ticketflow.filter.impl;


import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.cloud.nacos.NacosServiceInstance;
import com.ticketflow.context.ContextHandler;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.filter.AbstractServerFilter;
import com.ticketflow.threadlocal.BaseParameterHolder;
import com.ticketflow.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.ticketflow.constant.Constant.GRAY_FLAG_FALSE;
import static com.ticketflow.constant.Constant.GRAY_FLAG_TRUE;
import static com.ticketflow.constant.Constant.GRAY_PARAMETER;
import static com.ticketflow.constant.Constant.SERVER_GRAY;

/**
 * 灰度发布流量过滤器：根据请求的 gray 参数与 Nacos 实例 metadata 匹配，
 * 将请求路由到灰度版本的服务实例。
 *
 * 匹配逻辑：
 *   1. 从 header / ThreadLocal / 本地配置 获取 gray 标识
 *   2. 从 NacosServiceInstance.metadata 获取目标实例的灰度版本号
 *   3. 优先精确匹配，如果无匹配则按 isGray 开关决定走灰度还是正常
 *
 * 与 FeignRequestInterceptor + BaseParameterHolder 配合实现请求灰度标识
 * 在 Gateway → 下游服务的全链路传递
 */

@Slf4j
public class ServerGrayFilter extends AbstractServerFilter {
    
    /**
     * 此服务的灰度标识
     * */
    @Value(SERVER_GRAY)
    private String serverGray;
    
    private final ContextHandler contextHandler;
    
    private final Map<String,String> map = new HashMap<>();
    
    public ServerGrayFilter(ContextHandler contextHandler){
        this.contextHandler = contextHandler;
        this.map.put(GRAY_FLAG_FALSE, GRAY_FLAG_FALSE);
        this.map.put(GRAY_FLAG_TRUE, GRAY_FLAG_TRUE);
    }
    

    @Override
    public boolean doFilter(List<? extends ServiceInstance> servers, ServiceInstance server) {
        boolean result;
        try {
            String grayFromRequest = Optional.ofNullable(contextHandler.getValueFromHeader(GRAY_PARAMETER))
                    .filter(StringUtil::isNotEmpty)
                    .orElseGet(() -> BaseParameterHolder.getParameter(GRAY_PARAMETER));
            grayFromRequest = Optional.ofNullable(grayFromRequest).filter(StringUtil::isNotEmpty).orElse(serverGray);
            NacosServiceInstance nacosServiceInstance = (NacosServiceInstance)server;
            String grayFromMetaData = Optional.ofNullable(nacosServiceInstance.getMetadata())
                    .filter(CollectionUtil::isNotEmpty)
                    .map(metadata -> metadata.get(GRAY_PARAMETER))
                    .filter(StringUtil::isNotEmpty)
                    .orElse(GRAY_FLAG_FALSE);
            grayFromMetaData = Optional.ofNullable(map.get(grayFromMetaData.toLowerCase())).orElse(GRAY_FLAG_FALSE);
            grayFromRequest = Optional.ofNullable(map.get(grayFromRequest.toLowerCase())).orElse(GRAY_FLAG_FALSE);
            result = grayFromMetaData.equalsIgnoreCase(grayFromRequest);

            if (!result && grayFromRequest.equalsIgnoreCase(GRAY_FLAG_TRUE)) {
                if (CollectionUtil.isEmpty(servers)) {
                    throw new TicketFlowFrameException(BaseCode.SERVER_LIST_NOT_EXIST);
                }
                Map<String,String> map = new HashMap<>(servers.size());
                for (ServiceInstance serviceInstance : servers) {
                    NacosServiceInstance instance = (NacosServiceInstance)serviceInstance;
                    String balanceGray = Optional.ofNullable(instance.getMetadata())
                            .filter(CollectionUtil::isNotEmpty)
                            .map(metadata -> metadata.get(GRAY_PARAMETER))
                            .orElse(GRAY_FLAG_FALSE);
                    if (StringUtil.isEmpty(balanceGray)) {
                        balanceGray = GRAY_FLAG_FALSE;
                    }
                    map.put(balanceGray.toLowerCase(), balanceGray);
                }
                if(Objects.isNull(map.get(GRAY_FLAG_TRUE))) {
                    result = true;
                }
            }
        }catch (TicketFlowFrameException e) {
            throw e;
        }catch (Exception e) {
            result = false;
            log.error("CustomAwarePredicate#apply error",e);
        }
        return result;
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }
}