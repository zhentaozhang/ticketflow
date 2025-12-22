package com.ticketflow.service.composite;

import com.ticketflow.dto.ProgramRecommendListDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.CompositeCheckType;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.initialize.impl.composite.AbstractComposite;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 节目推荐查询参数校验。
 * areaId / parentProgramCategoryId / programId 至少需要传一个
 */
@Component
public class ProgramRecommendCheckHandler extends AbstractComposite<ProgramRecommendListDto> {
    
    /**
     * 校验推荐查询参数：areaId / parentProgramCategoryId / programId 至少提供一个。
     *
     * @param param 推荐列表查询参数
     */
    @Override
    protected void execute(final ProgramRecommendListDto param) {
        if (Objects.isNull(param.getAreaId()) && 
                Objects.isNull(param.getParentProgramCategoryId()) &&
                Objects.isNull(param.getProgramId())) {
            throw new TicketFlowFrameException(BaseCode.PARAMETERS_CANNOT_BE_EMPTY);
        }
    }
    
    /**
     * 返回校验链类型（节目推荐校验）。
     *
     * @return PROGRAM_RECOMMEND_CHECK
     */
    @Override
    public String type() {
        return CompositeCheckType.PROGRAM_RECOMMEND_CHECK.getValue();
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
     * 执行层级（第 1 层）。
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
