package com.ticketflow.service.composite.impl;


import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.BusinessStatus;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.service.ProgramService;
import com.ticketflow.service.composite.AbstractProgramCheckHandler;
import com.ticketflow.vo.ProgramVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 节目详情校验（Composite BFS 第二级校验节点）。
 * 校验内容：
 *   非选座节目不允许传入 seatDtoList（permitChooseSeat=NO）
 *   购买数量不超过 perOrderLimitPurchaseCount（单笔限购）
 *
 * 依赖 ProgramService.detailV2() 获取节目配置数据
 */
@Component
public class ProgramDetailCheckHandler extends AbstractProgramCheckHandler {
    
    @Autowired
    private ProgramService programService;
    
    /**
     * 校验节目详情：非选座节目不允许传 seatDtoList，购买数量不超过单笔限购。
     *
     * @param programOrderCreateDto 订单创建参数
     */
    @Override
    protected void execute(final ProgramOrderCreateDto programOrderCreateDto) {
        // 轻量两级缓存查询（本地 Caffeine → Redis），替代完整 detailV2：
        // 下单校验只需 permitChooseSeat / perOrderLimitPurchaseCount，
        // 避免每次下单走完整 getDetailV2 多级缓存链 + RBloomFilter，降低每单 Redis 命令数。
        ProgramVo programVo = programService.simpleGetByIdMultipleCache(programOrderCreateDto.getProgramId());
        if (Objects.isNull(programVo)) {
            throw new TicketFlowFrameException(BaseCode.PROGRAM_NOT_EXIST);
        }
        if (programVo.getPermitChooseSeat().equals(BusinessStatus.NO.getCode())) {
            if (Objects.nonNull(programOrderCreateDto.getSeatDtoList())) {
                throw new TicketFlowFrameException(BaseCode.PROGRAM_NOT_ALLOW_CHOOSE_SEAT);
            }
        }
        Integer seatCount = Optional.ofNullable(programOrderCreateDto.getSeatDtoList()).map(List::size).orElse(0);
        Integer ticketCount = Optional.ofNullable(programOrderCreateDto.getTicketCount()).orElse(0);
        if (seatCount > programVo.getPerOrderLimitPurchaseCount() || ticketCount > programVo.getPerOrderLimitPurchaseCount()) {
            throw new TicketFlowFrameException(BaseCode.PER_ORDER_PURCHASE_COUNT_OVER_LIMIT);
        }
    }
    
    /**
     * 父节点顺序（挂载在 order=1 的父节点下）。
     *
     * @return 1
     */
    @Override
    public Integer executeParentOrder() {
        return 1;
    }

    /**
     * 执行层级（第 2 层，参数校验之后执行）。
     *
     * @return 2
     */
    @Override
    public Integer executeTier() {
        return 2;
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
