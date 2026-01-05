package com.ticketflow.client;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.TicketUserListDto;
import com.ticketflow.dto.UserGetAndTicketUserListDto;
import com.ticketflow.dto.UserIdDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.vo.UserGetAndTicketUserListVo;
import com.ticketflow.vo.TicketUserVo;
import com.ticketflow.vo.UserVo;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户服务Feign降级。用户服务不可用时的降级处理。
 */
@Component
public class UserClientFallback implements UserClient {
    
    @Override
    public ApiResponse<UserVo> getById(final UserIdDto userIdDto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);  // 查不到用户 → 拒绝登录/下单
    }
    
    @Override
    public ApiResponse<List<TicketUserVo>> list(final TicketUserListDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
    
    @Override
    public ApiResponse<UserGetAndTicketUserListVo> getUserAndTicketUserList(final UserGetAndTicketUserListDto dto) {
        return ApiResponse.error(BaseCode.SYSTEM_ERROR);
    }
}
