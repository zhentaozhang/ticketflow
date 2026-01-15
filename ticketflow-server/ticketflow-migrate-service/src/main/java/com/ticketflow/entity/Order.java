package com.ticketflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ticketflow.data.BaseTableData;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 订单。迁移服务中的订单实体，与 order-service 的 Order 实体对应，
 * 用于数据迁移/同步场景。完整记录订单的商品、支付、用户等信息。
 * 数据表: d_order
 */
@Data
@TableName("d_order")
public class Order extends BaseTableData implements Serializable {

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
     * 记录id
     */
    private Long identifierId;

    /**
     * 节目表id
     */
    private Long programId;
    
    /**
     * 节目图片介绍
     * */
    private String programItemPicture;

    /**
     * 用户id
     */
    private Long userId;
    
    /**
     * 节目标题
     * */
    private String programTitle;
    
    /**
     * 节目地点
     * */
    private String programPlace;
    
    /**
     * 节目演出时间
     * */
    private Date programShowTime;
    
    /**
     * 节目是否允许选座 1:允许选座 0:不允许选座
     * */
    private Integer programPermitChooseSeat;

    /**
     * 配送方式
     */
    private String distributionMode;

    /**
     * 取票方式
     */
    private String takeTicketMode;

    /**
     * 订单价格
     */
    private BigDecimal orderPrice;

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
