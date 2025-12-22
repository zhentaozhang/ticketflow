package com.ticketflow.service.composite.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.dto.SeatDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.service.composite.AbstractProgramCheckHandler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 订单创建参数校验（Composite BFS 第一级校验节点）。
 * 校验内容：
 *   选座模式：seatDtoList 每项 id/ticketCategoryId/rowCode/colCode/price 非空
 *   自动匹配：ticketCategoryId/ticketCount 非空且 > 0
 *   购票人去重 + 人数与座位数一致
 */
@Component
public class ProgramOrderCreateParamCheckHandler extends AbstractProgramCheckHandler {
    
    /**
     * 校验订单创建参数：购票人去重、选座字段完整性或票数合法性。
     *
     * @param programOrderCreateDto 订单创建参数
     */
    @Override
    protected void execute(final ProgramOrderCreateDto programOrderCreateDto) {
        List<SeatDto> seatDtoList = programOrderCreateDto.getSeatDtoList();
        List<Long> ticketUserIdList = programOrderCreateDto.getTicketUserIdList();
        Map<Long, List<Long>> ticketUserIdMap = 
                ticketUserIdList.stream().collect(Collectors.groupingBy(ticketUserId -> ticketUserId));
        for (List<Long> value : ticketUserIdMap.values()) {
            if (value.size() > 1) {
                throw new TicketFlowFrameException(BaseCode.TICKET_USER_ID_REPEAT);
            }
        }
        if (CollectionUtil.isNotEmpty(seatDtoList)) {
            if (seatDtoList.size() != programOrderCreateDto.getTicketUserIdList().size()) {
                throw new TicketFlowFrameException(BaseCode.TICKET_USER_COUNT_UNEQUAL_SEAT_COUNT);
            }
            for (SeatDto seatDto : seatDtoList) {
                if (Objects.isNull(seatDto.getId())) {
                    throw new TicketFlowFrameException(BaseCode.SEAT_ID_EMPTY);
                }
                if (Objects.isNull(seatDto.getTicketCategoryId())) {
                    throw new TicketFlowFrameException(BaseCode.SEAT_TICKET_CATEGORY_ID_EMPTY);
                }
                if (Objects.isNull(seatDto.getRowCode())) {
                    throw new TicketFlowFrameException(BaseCode.SEAT_ROW_CODE_EMPTY);
                }
                if (Objects.isNull(seatDto.getColCode())) {
                    throw new TicketFlowFrameException(BaseCode.SEAT_COL_CODE_EMPTY);
                }
                if (Objects.isNull(seatDto.getPrice())) {
                    throw new TicketFlowFrameException(BaseCode.SEAT_PRICE_EMPTY);
                }
            }
        }else {
            if (Objects.isNull(programOrderCreateDto.getTicketCategoryId())) {
                throw new TicketFlowFrameException(BaseCode.TICKET_CATEGORY_NOT_EXIST);
            }
            if (Objects.isNull(programOrderCreateDto.getTicketCount())) {
                throw new TicketFlowFrameException(BaseCode.TICKET_COUNT_NOT_EXIST);
            }
            if (programOrderCreateDto.getTicketCount() <= 0) {
                throw new TicketFlowFrameException(BaseCode.TICKET_COUNT_ERROR);
            }
        }
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
     * 执行层级（第 1 层，最先执行）。
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
