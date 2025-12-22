package com.ticketflow.service.kafka;

import com.ticketflow.exception.TicketFlowFrameException;

/**
 * 订单创建MQ消息。封装订单创建成功后的MQ消息内容，用于异步通知节目服务更新余票。
 */
public class CreateOrderMqDomain {

    public String orderNumber;
    
    public TicketFlowFrameException ticketFlowFrameException;
}
