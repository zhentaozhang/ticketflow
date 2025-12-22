package com.ticketflow.service.composite;

import com.ticketflow.dto.ProgramGetDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.CompositeCheckType;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.handler.BloomFilterHandler;
import com.ticketflow.initialize.impl.composite.AbstractComposite;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 节目详情查询第一道校验：BloomFilter 判断 programId 是否存在。
 * executeTier=1, executeOrder=1，在 BFS 树中最早执行。
 * BloomFilter 不存在该 id → 直接抛出 PROGRAM_NOT_EXIST（快速失败，避免缓存穿透到 DB）
 */
@Component
public class ProgramBloomFilterCheckHandler extends AbstractComposite<ProgramGetDto> {
    
    @Autowired
    private BloomFilterHandler bloomFilterHandler;
    
    /**
     * 校验 programId 是否在 BloomFilter 中存在，不存在则快速失败。
     *
     * @param param 节目查询参数
     */
    @Override
    protected void execute(final ProgramGetDto param) {
        boolean contains = bloomFilterHandler.contains(String.valueOf(param.getId()));
        if (!contains) {
            throw new TicketFlowFrameException(BaseCode.PROGRAM_NOT_EXIST);
        }
    }
    
    /**
     * 返回校验链类型（节目详情校验）。
     *
     * @return PROGRAM_DETAIL_CHECK
     */
    @Override
    public String type() {
        return CompositeCheckType.PROGRAM_DETAIL_CHECK.getValue();
    }
    
    /**
     * 父节点顺序（0 = 根节点）。
     *
     * @return 0
     */
    @Override
    public Integer executeParentOrder() {
        return 0;
    }
    
    /**
     * 执行层级（第 1 层，最早执行）。
     *
     * @return 1
     */
    @Override
    public Integer executeTier() {
        return 1;
    }
    
    /**
     * 同层级中的执行顺序（第 1 个）。
     *
     * @return 1
     */
    @Override
    public Integer executeOrder() {
        return 1;
    }
}
