package com.ticketflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 座位管理VO。管理端查看和编辑座位的数据结构。
 */
@Data
@Schema(title="SeatManageVo", description ="座位")
public class SeatManageVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
    
    
    @Schema(name ="id", type ="Long", description ="座位id")
    private Long id;
    
    @Schema(name ="programId", type ="Long", description ="节目表id")
    private Long programId;
    
    @Schema(name ="ticketCategoryId", type ="Long", description ="节目票档id")
    private Long ticketCategoryId;
    
    @Schema(name ="rowCode", type ="Integer", description ="排号")
    private Integer rowCode;
  
    @Schema(name ="colCode", type ="Integer", description ="列号")
    private Integer colCode;
    
    @Schema(name ="price", type ="BigDecimal", description ="座位价格")
    private BigDecimal price;
    
    @Schema(name ="sellStatus", type ="Integer", description ="数据库的座位状态 1未售卖 2锁定 3已售卖")
    private Integer dbSellStatus;
    
    @Schema(name ="dbSellStatusName", type ="Integer", description ="数据库的座位状态名字")
    private String dbSellStatusName;
    
    @Schema(name ="redisSellStatus", type ="Integer", description ="Redis的座位状态 1未售卖 2锁定 3已售卖")
    private Integer redisSellStatus;
    
    @Schema(name ="redisSellStatusName", type ="Integer", description ="Redis的座位状态名字")
    private String redisSellStatusName;
}
