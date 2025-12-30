package com.ticketflow.simulation.constant;

/**
 * 压测模拟常量。定义压测场景中的模拟用户、节目和票档等默认数据。
 */
public class SimulationOrderConstant {
    
    public static final String USER_LOGIN_URL = "http://127.0.0.1:6082/user/login";
    
    public static final String TICKET_USER_LIST_URL = "http://127.0.0.1:6082/ticket/user/list";
    
    public static final String PROGRAM_DETAIL_URL = "http://127.0.0.1:6086/program/detail";
    
    public static final String CREATE_PROGRAM_ORDER_URL = "http://127.0.0.1:6086/program/order/create/v4";
}
