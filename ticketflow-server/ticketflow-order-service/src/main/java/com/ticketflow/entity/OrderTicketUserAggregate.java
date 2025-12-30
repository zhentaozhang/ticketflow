package com.ticketflow.entity;

import lombok.Data;

/**
 * 购票人订单聚合统计。非数据库实体，统计一个订单下购票人的数量，
 * 用于业务校验和展示。
 */
@Data
public class OrderTicketUserAggregate {
    
    private Long orderNumber;
    
    private Integer orderTicketUserCount;
}
