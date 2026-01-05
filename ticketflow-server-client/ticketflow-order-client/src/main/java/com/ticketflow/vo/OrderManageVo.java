package com.ticketflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 订单列表 vo
 */
@Data
@Schema(title="OrderManageVo", description ="订单列表")
public class OrderManageVo {
    
    @Schema(name ="orderNumber", type ="Long", description ="订单编号")
    private Long orderNumber;

    @Schema(name ="userId", type ="Long", description ="用户id")
    private Long userId;
    
    @Schema(name ="programTitle", type ="String", description ="节目标题")
    private String programTitle;
    
    @Schema(name ="programShowTime", type ="Date", description ="节目演出时间")
    private Date programShowTime;

    @Schema(name ="orderPrice", type ="BigDecimal", description ="订单价格")
    private BigDecimal orderPrice;
    
    @Schema(name ="orderStatus", type ="Integer", description ="订单状态 1:未支付 2:已取消 3:已支付 4:已退单")
    private Integer orderStatus;
    
    @Schema(name ="orderStatusName", type ="String", description ="订单状态名字")
    private String orderStatusName;
    
    @Schema(name ="createOrderTime", type ="Date", description ="订单生成时间")
    private Date createOrderTime;
    
    @Schema(name ="payOrderTime", type ="Date", description ="订单支付时间")
    private Date payOrderTime;
    
    @Schema(name ="createOrderTime", type ="Date", description ="订单取消时间")
    private Date cancelOrderTime;
    
    @Schema(name ="orderTicketUserManageVoList", type ="List", description ="购票人订单集合")
    private List<OrderTicketUserManageVo> orderTicketUserManageVoList;
}
