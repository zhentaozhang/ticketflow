package com.ticketflow.service.init;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.ticketflow.BusinessThreadPool;
import com.ticketflow.entity.User;
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
            // 键集分页逐批加载，避免一次性把整张用户表（含全部字段）读进内存
            int pageSize = UserService.BLOOM_FILTER_PAGE_SIZE;
            Long lastId = null;
            while (true) {
                List<User> userBatch = userService.selectUserBatch(lastId, pageSize);
                if (CollectionUtil.isEmpty(userBatch)) {
                    break;
                }
                for (User user : userBatch) {
                    if (StrUtil.isNotBlank(user.getMobile())) {
                        bloomFilterHandler.add(user.getMobile());
                    }
                }
                lastId = userBatch.get(userBatch.size() - 1).getId();
                if (userBatch.size() < pageSize) {
                    break;
                }
            }
        });
    }
}
