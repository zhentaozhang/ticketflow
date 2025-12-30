package com.ticketflow.domain;

import com.ticketflow.dto.OrderTicketUserCreateDto;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 订单创建数据。封装创建订单所需的完整业务数据，包括节目、票档、座位、购票人、价格等信息。
 */
@Data
public class OrderCreateDomain {
    
    private Long identifierId;
    
    private Long orderNumber;
 
    private Long programId;
   
    private String programItemPicture;
    
    private Long userId;
    
    private String programTitle;
    
    private String programPlace;
    
    private Date programShowTime;
    
    private Integer programPermitChooseSeat;
    
    private String distributionMode;
    
    private String takeTicketMode;
    
    private BigDecimal orderPrice;
    
    private Date createOrderTime;
    
    private List<OrderTicketUserCreateDto> orderTicketUserCreateDtoList;
    
    private Integer orderVersion;
    
}
