package com.ticketflow.service.composite.register.impl;

import com.ticketflow.dto.UserRegisterDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.service.composite.register.AbstractUserRegisterCheckHandler;
import com.ticketflow.service.tool.RequestCounter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 组合验证链：检查单日同一手机号的注册量。
 * 通过 Redis 计数器判断是否超过阈值，超限则抛异常
 */
@Component
public class UserRegisterCountCheckHandler extends AbstractUserRegisterCheckHandler {

    @Autowired
    private RequestCounter requestCounter;

    @Override
    protected void execute(final UserRegisterDto param) {
        // 通过 Redis 计数器判断每日注册是否超限
        boolean result = requestCounter.onRequest();
        if (result) {
            throw new TicketFlowFrameException(BaseCode.USER_REGISTER_FREQUENCY);
        }
    }

    @Override
    public Integer executeParentOrder() {
        return 1;
    }

    @Override
    public Integer executeTier() {
        return 2;
    }

    @Override
    public Integer executeOrder() {
        return 1;
    }
}
