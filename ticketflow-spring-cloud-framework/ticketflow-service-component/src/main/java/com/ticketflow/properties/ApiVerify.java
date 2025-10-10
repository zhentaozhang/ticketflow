package com.ticketflow.properties;

import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.util.StringUtil;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * API校验器。提供接口访问密码验证，用于保障内部API的安全性。
 **/
public class ApiVerify {
    
    private final String apiPassword = "api_password";
    
    private final BackManageProperties backManageProperties;
    
    public ApiVerify(BackManageProperties backManageProperties) {
        this.backManageProperties = backManageProperties;
    }
    
    public void verifyApi() {
        if (backManageProperties.getApiPasswordCall()) {
            String password = Optional.ofNullable(RequestContextHolder.getRequestAttributes())
                    .map(requestAttributes -> ((ServletRequestAttributes) requestAttributes).getRequest())
                    .map(request -> request.getHeader(apiPassword))
                    .orElseGet(() -> null);
            if (StringUtil.isEmpty(password)) {
                throw new TicketFlowFrameException(BaseCode.API_CALL_NEED_PASSWORD);
            }
            if (!password.equals(backManageProperties.getApiPassword())) {
                throw new TicketFlowFrameException(BaseCode.API_CALL_PASSWORD_ERROR);
            }
        }
    }
}
