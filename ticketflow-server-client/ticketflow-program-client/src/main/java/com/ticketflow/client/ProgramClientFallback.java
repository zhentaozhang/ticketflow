package com.ticketflow.client;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.*;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.vo.ProgramRecordTaskVo;
import com.ticketflow.vo.TicketCategoryDetailVo;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 节目服务Feign降级。节目服务不可用时的降级处理。
 */
@Component
public class ProgramClientFallback implements ProgramClient {

    @Override
    public ApiResponse<Boolean> operateSeatLockAndTicketCategoryRemainNumber(final ReduceRemainNumberDto reduceRemainNumberDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);  // 锁座失败 → 下单失败，用户重试
    }

    @Override
    public ApiResponse<List<TicketCategoryDetailVo>> selectList(final TicketCategoryListDto ticketCategoryDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }

    @Override
    public ApiResponse<List<Long>> allList() {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }

    @Override
    public ApiResponse<List<ProgramRecordTaskVo>> select(final ProgramRecordTaskListDto programRecordTaskListDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }

    @Override
    public ApiResponse<Integer> update(final ProgramRecordTaskUpdateDto programRecordTaskUpdateDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }

    @Override
    public ApiResponse<Integer> add(final ProgramRecordTaskAddDto orderTicketUserRecordAddDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }

    @Override
    public ApiResponse<Boolean> operateProgramData(final ProgramOperateDataDto programOperateDataDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }

    @Override
    public ApiResponse<List<TicketCategoryDetailVo>> selectListByProgram(TicketCategoryListByProgramDto ticketCategoryListByProgramDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
}
