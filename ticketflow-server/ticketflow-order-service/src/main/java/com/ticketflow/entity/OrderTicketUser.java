package com.ticketflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ticketflow.data.BaseTableData;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 购票人订单明细。一个订单(Order)下每个购票人的独立子订单，
 * 包含座位、票档、价格、支付方式和订单状态。
 * 数据表: d_order_ticket_user
 */
@Data
@TableName("d_order_ticket_user")
public class OrderTicketUser extends BaseTableData implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    private Long id;
    
    /**
     * 订单编号
     * */
    private Long orderNumber;

    /**
     * 节目表id
     */
    private Long programId;

    /**
     * 用户id
     */
    private Long userId;

    /**
     * 购票人id
     */
    private Long ticketUserId;

    /**
     * 座位id
     */
    private Long seatId;
    
    /**
     * 座位信息
     * */
    private String seatInfo;
    
    /**
     * 节目票档id
     * */
    private Long ticketCategoryId;

    /**
     * 订单价格
     */
    private BigDecimal orderPrice;

    /**
     * 支付订单价格
     */
    private BigDecimal payOrderPrice;

    /**
     * 支付订单方式
     */
    private Integer payOrderType;

    /**
     * 订单状态 1:未支付 2:已取消 3:已支付 4:已退单
     */
    private Integer orderStatus;
    
    /**
     * 对账状态 1:未对账 -1:对账完成有问题 2:对账完成没有问题 3:对账有问题处理完毕
     */
    private Integer reconciliationStatus;

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
