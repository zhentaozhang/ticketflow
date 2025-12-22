package com.ticketflow.service.init;

import com.ticketflow.BusinessThreadPool;
import com.ticketflow.initialize.base.AbstractApplicationPostConstructHandler;
import com.ticketflow.service.ProgramCategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 启动初始化：加载节目分类数据到 Redis（@PostConstruct 阶段）。
 * 通过 ProgramCategoryService.programCategoryRedisDataInit() 将分类列表写入
 * Redis Hash，供节目查询页使用。
 * <p>
 * 执行顺序 order=1（最早执行）
 */
@Component
public class ProgramCategoryInitData extends AbstractApplicationPostConstructHandler {

    @Autowired
    private ProgramCategoryService programCategoryService;


    /**
     * 执行优先级
     *
     * @return 1（最先执行）
     */
    @Override
    public Integer executeOrder() {
        return 1;
    }

    /**
     * 异步加载节目分类数据到 Redis
     *
     * @param context Spring 应用上下文
     */
    @Override
    public void executeInit(final ConfigurableApplicationContext context) {
        BusinessThreadPool.execute(() -> {
            programCategoryService.programCategoryRedisDataInit();
        });
    }
}
