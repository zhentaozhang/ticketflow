package com.ticketflow.service.init;

import cn.hutool.core.collection.CollectionUtil;
import com.ticketflow.BusinessThreadPool;
import com.ticketflow.handler.BloomFilterHandler;
import com.ticketflow.initialize.base.AbstractApplicationPostConstructHandler;
import com.ticketflow.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户布隆过滤器初始化。启动时将用户ID加载到布隆过滤器，用于用户查询的快速预判。
 */
@Component
public class UserBloomFilterInitData extends AbstractApplicationPostConstructHandler {
    
    @Autowired
    private BloomFilterHandler bloomFilterHandler;
    
    @Autowired
    private UserService userService;
    
    
    @Override
    public Integer executeOrder() {
        return 1;
    }
    
    @Override
    public void executeInit(final ConfigurableApplicationContext context) {
        BusinessThreadPool.execute(() -> {
            List<String> allMobile = userService.getAllMobile();
            if (CollectionUtil.isNotEmpty(allMobile)) {
                allMobile.forEach(mobile -> bloomFilterHandler.add(mobile));
            }
        });
    }
}
