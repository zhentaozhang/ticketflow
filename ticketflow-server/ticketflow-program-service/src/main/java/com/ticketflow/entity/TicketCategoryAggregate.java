package com.ticketflow.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 票档统计。非数据库实体，用于统计节目所有票档的价格区间（最低价~最高价），
 * 在节目列表/详情页展示价格范围。
 */
@Data
public class TicketCategoryAggregate implements Serializable {
    
    /**
     * 节目表id
     */
    private Long programId;
    
    /**
     * 最低价格
     */
    private BigDecimal minPrice;
    
    /**
     * 最高价格
     */
    private BigDecimal maxPrice;
}
