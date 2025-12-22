package com.ticketflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ticketflow.data.BaseTableData;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 票档实体 —— 对应数据库表 d_ticket_category。
 *
 * 一个节目（Program）有多个票档，不同票档价格不同。
 * 比如周杰伦演唱会：
 *   票档 A：内场 VIP    ￥1980（totalNumber=500, remainNumber=120）
 *   票档 B：看台 A 区   ￥980（totalNumber=1000, remainNumber=320）
 *   票档 C：看台 B 区   ￥580（totalNumber=2000, remainNumber=880）
 *
 * 核心逻辑：每次下单扣 remainNumber（减库存），取消订单加 remainNumber（还库存）。
 * 扣减操作在 TicketCategoryMapper.xml 里用 SQL 实现，通过 WHERE 条件保证不超卖。
 */
@Data
@TableName("d_ticket_category")
public class TicketCategory extends BaseTableData implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    private Long id;

    /**
     * 关联节目表 d_program 的 id。
     * 一个 Program 下有多个 TicketCategory。
     */
    private Long programId;

    /**
     * 票档介绍，如"内场 VIP 区"、"看台 A 区"
     */
    private String introduce;

    /**
     * 本档票价，如 1980.00
     */
    private BigDecimal price;

    /**
     * 本档总票数（开售时就定死了，不变）
     */
    private Long totalNumber;

    /**
     * 本档剩余票数（高并发下频繁修改）
     * remainNumber = totalNumber - 已卖出的数量
     * 每次下单减这个数，取消订单加回来
     */
    private Long remainNumber;
}
