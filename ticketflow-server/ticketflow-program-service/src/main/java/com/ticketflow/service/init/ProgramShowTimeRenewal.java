package com.ticketflow.service.init;

import com.ticketflow.core.SpringUtil;
import com.ticketflow.initialize.base.AbstractApplicationPostConstructHandler;
import com.ticketflow.service.ProgramService;
import com.ticketflow.service.ProgramShowTimeService;
import com.ticketflow.util.BusinessEsHandle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 启动初始化：检查节目演出时间是否已过期，自动更新状态并清理缓存。
 * 对已过期的节目：
 * 1. DB 更新 show_time 状态为已结束
 * 2. 删除 ES 索引（触发全量重建）
 * 3. 删除 Redis 缓存（下回查询触发从 DB 重新加载）
 * 4. 删除本地 Caffeine 缓存（通过 Redis Stream 广播）
 * <p>
 * 执行顺序 order=2（在 ProgramCategoryInitData 之后）
 */
@Component
public class ProgramShowTimeRenewal extends AbstractApplicationPostConstructHandler {

    @Autowired
    private ProgramShowTimeService programShowTimeService;

    @Autowired
    private ProgramService programService;

    @Autowired
    private BusinessEsHandle businessEsHandle;

    /**
     * 执行优先级
     *
     * @return 2（在 ProgramCategoryInitData 之后，ProgramElasticsearchInitData 之前执行）
     */
    @Override
    public Integer executeOrder() {
        return 2;
    }

    /**
     * 检查并更新过期演出时间，清理相关缓存（ES 索引、Redis、本地缓存）
     *
     * @param context Spring 应用上下文
     */
    @Override
    public void executeInit(final ConfigurableApplicationContext context) {
        //判断节目演出时间是否过期，如果过期了，则更新时间，并返回已经更新演出时间的节目id
        Set<Long> programIdSet = programShowTimeService.renewal();
        if (!programIdSet.isEmpty()) {
            //如果更新了，将elasticsearch的整个索引和数据都删除
            boolean result = businessEsHandle.checkIndex(SpringUtil.getPrefixDistinctionName() + "-" +
                    ProgramDocumentParamName.INDEX_NAME, ProgramDocumentParamName.INDEX_TYPE);
            if (result) {
                businessEsHandle.deleteIndex(SpringUtil.getPrefixDistinctionName() + "-" +
                        ProgramDocumentParamName.INDEX_NAME);
            }
            for (Long programId : programIdSet) {
                //将redis中的数据也删除
                programService.delRedisData(programId);
                //将本地缓存数据也删除
                programService.delLocalCache(programId);
            }
        }
    }
}
