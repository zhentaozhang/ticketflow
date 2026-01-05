package com.ticketflow.client;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.AreaGetDto;
import com.ticketflow.dto.AreaSelectDto;
import com.ticketflow.dto.GetChannelDataByCodeDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.vo.AreaVo;
import com.ticketflow.vo.GetChannelDataVo;
import com.ticketflow.vo.TokenDataVo;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基础数据服务Feign降级。基础数据服务不可用时的降级处理。
 */
@Component
public class BaseDataClientFallback implements BaseDataClient {
    @Override
    public ApiResponse<GetChannelDataVo> getByCode(final GetChannelDataByCodeDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);  // gateway鉴权失败直接拒绝请求
    }

    @Override
    public ApiResponse<TokenDataVo> get() {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }

    @Override
    public ApiResponse<List<AreaVo>> selectByIdList(final AreaSelectDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }

    @Override
    public ApiResponse<AreaVo> getById(final AreaGetDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
}
