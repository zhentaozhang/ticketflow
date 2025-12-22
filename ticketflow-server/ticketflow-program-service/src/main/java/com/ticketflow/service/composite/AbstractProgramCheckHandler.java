package com.ticketflow.service.composite;

import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.enums.CompositeCheckType;
import com.ticketflow.initialize.impl.composite.AbstractComposite;

/**
 * 节目订单创建参数验证基类（Composite BFS 链中的节点）。
 * 继承 AbstractComposite<ProgramOrderCreateDto>，固定 type = PROGRAM_ORDER_CREATE_CHECK。
 * 子类实现 execute() 完成单项校验（参数/用户/频率等），
 * 由 CompositeContainer 按 order 排序后 BFS 执行。
 *
 * 校验链：参数检查 → 用户存在性 → BloomFilter 防穿透 → 购票人权限 → 频率限制
 */
public abstract class AbstractProgramCheckHandler extends AbstractComposite<ProgramOrderCreateDto> {
    
    /**
     * 返回校验链类型（订单创建校验）。
     *
     * @return PROGRAM_ORDER_CREATE_CHECK
     */
    @Override
    public String type() {
        return CompositeCheckType.PROGRAM_ORDER_CREATE_CHECK.getValue();
    }
}
