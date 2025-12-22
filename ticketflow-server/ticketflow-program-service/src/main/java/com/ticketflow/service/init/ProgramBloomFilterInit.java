package com.ticketflow.service.init;

import cn.hutool.core.collection.CollectionUtil;
import com.ticketflow.handler.BloomFilterHandler;
import com.ticketflow.initialize.base.AbstractApplicationPostConstructHandler;
import com.ticketflow.service.ProgramService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动初始化：将所有节目 ID 写入 BloomFilter（@PostConstruct 阶段）。
 * BloomFilter 用于缓存穿透防护——请求查询节目详情时，
 * ProgramBloomFilterCheckHandler 先判断 id 是否可能有效。
 *
 * 执行顺序 order=4（在 ProgramCategoryInitData、ProgramShowTimeRenewal、
 * ProgramElasticsearchInitData 之后）
 */
@Component
public class ProgramBloomFilterInit extends AbstractApplicationPostConstructHandler {
    
    @Autowired
    private ProgramService programService;
    
    @Autowired
    private BloomFilterHandler bloomFilterHandler;
    
    /**
     * 执行优先级
     *
     * @return 4（在所有数据初始化完成之后最后执行）
     */
    @Override
    public Integer executeOrder() {
        return 4;
    }
    
    /**
     * 将所有节目 ID 写入 BloomFilter，用于缓存穿透防护
     *
     * @param context Spring 应用上下文
     */
    @Override
    public void executeInit(final ConfigurableApplicationContext context) {
        List<Long> allProgramIdList = programService.getAllProgramIdList();
        if (CollectionUtil.isEmpty(allProgramIdList)) {
            return;
        }
        allProgramIdList.forEach(programId -> bloomFilterHandler.add(String.valueOf(programId)));
    }
}
