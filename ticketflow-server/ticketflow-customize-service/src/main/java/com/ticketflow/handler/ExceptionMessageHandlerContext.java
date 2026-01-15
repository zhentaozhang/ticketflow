package com.ticketflow.handler;

import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.MessageType;
import com.ticketflow.exception.TicketFlowFrameException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 异常消息处理器上下文。根据消息类型分发表异常消息到对应的处理器。
 */
@Component
public class ExceptionMessageHandlerContext {

    @Autowired
    private List<ExceptionMessageHandler> exceptionMessageHandlerList;
    
    private final Map<MessageType, ExceptionMessageHandler> exceptionMessageHandlerMap = new HashMap<>();
    
    @PostConstruct
    public void init() {
        for (ExceptionMessageHandler exceptionMessageHandler : exceptionMessageHandlerList) {
            exceptionMessageHandlerMap.put(exceptionMessageHandler.getMessageType(), exceptionMessageHandler);
        }
    }
    
    public ExceptionMessageHandler getExceptionMessageHandler(MessageType messageType) {
        return Optional.ofNullable(exceptionMessageHandlerMap.get(messageType)).orElseThrow(
                () -> new TicketFlowFrameException(BaseCode.MESSAGE_TYPE_NOT_EXIST));
    }
}
