package com.ticketflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 废弃订单列表 vo
 */
@Data
@Schema(title="DiscardOrderManageVo", description ="废弃订单列表")
public class DiscardOrderManageVo {
    
    @Schema(name ="orderNumber", type ="Long", description ="订单编号")
    private Long orderNumber;

    @Schema(name ="userId", type ="Long", description ="用户id")
    private Long userId;
    
    @Schema(name ="programTitle", type ="String", description ="节目标题")
    private String programTitle;

    @Schema(name ="orderPrice", type ="BigDecimal", description ="订单价格")
    private BigDecimal orderPrice;
    
    @Schema(name ="createOrderTime", type ="Date", description ="生成订单时间")
    private Date createOrderTime;
    
    @Schema(name ="discardOrderReason", type ="Integer", description ="废弃原因枚举值")
    private Integer discardOrderReason;
    
    @Schema(name ="discardOrderReasonName", type ="String", description ="废弃原因")
    private String discardOrderReasonName;
    
    @Schema(name ="orderTicketUserManageVoList", type ="List", description ="废弃购票人订单集合")
    private List<DiscardOrderTicketUserManageVo> discardOrderTicketUserManageVo;
}
