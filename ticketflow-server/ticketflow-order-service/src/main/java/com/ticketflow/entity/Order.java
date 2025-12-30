package com.ticketflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ticketflow.data.BaseTableData;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 订单实体。核心业务实体，记录用户购票订单的完整信息，关联购票人数据和节目场次。
 */
@Data
@TableName("d_order")
public class Order extends BaseTableData implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    
    private Long orderNumber;
    
    private Long identifierId;

    private Long programId;
    
    private String programItemPicture;

    private Long userId;
    
    private String programTitle;
    
    private String programPlace;
    
    private Date programShowTime;
    
    /** 1:允许选座 0:不允许选座 */
    private Integer programPermitChooseSeat;

    private String distributionMode;

    private String takeTicketMode;

    private BigDecimal orderPrice;

    private Integer payOrderType;

    /** 1:未支付 2:已取消 3:已支付 4:已退单 */
    private Integer orderStatus;
    
    /** 1:未对账 -1:对账完成有问题 2:对账完成没有问题 3:对账有问题处理完毕 */
    private Integer reconciliationStatus;
    
    /**
     * 创建订单的版本 1 2 3 4
     * */
    private Integer orderVersion;

    /**
     * 生成订单时间
     */
    private Date createOrderTime;

    /**
     * 取消订单时间
     */
    private Date cancelOrderTime;

    /**
     * 支付订单时间
     */
    private Date payOrderTime;
}
