package com.couponseckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 秒杀活动（对应表 flash_sale_activity，库存内嵌）。
 */
@Data
@TableName("flash_sale_activity")
public class FlashSaleActivity {

    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_NOT_STARTED = 1;
    public static final int STATUS_ONGOING = 2;
    public static final int STATUS_ENDED = 3;
    public static final int STATUS_OFFLINE = 4;

    @TableId(type = IdType.INPUT)
    private Long id;

    private String activityNo;

    private Long couponTemplateId;

    private String activityName;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer totalStock;

    private Integer stock;

    private Integer perUserLimit;

    private Integer status;

    /** 乐观锁版本 */
    @Version
    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
