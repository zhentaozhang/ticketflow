package com.ticketflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ticketflow.data.BaseTableData;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 座位实体 —— 对应数据库表 d_seat。
 *
 * 一场演出（Program）有多个座位，每个座位属于一个票档（TicketCategory，如 VIP区/看台区）。
 * 座位有 3 种状态：1=未售卖 2=锁定 3=已售卖。
 * 下单时：
 *   1. 先检查 sellStatus = 1（未售卖）
 *   2. 改成 2（锁定，防止别人同时下单）
 *   3. 支付成功再改成 3（已售卖）
 *   4. 支付失败/取消改回 1（未售卖），同时归还库存
 */
@Data
@TableName("d_seat")
public class Seat extends BaseTableData implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID。
     * 加了 @TableId(type = IdType.AUTO) 表示数据库自增。
     * Program.id 没加这个注解是因为 Program 的 ID 由雪花算法生成，不自增。
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 关联节目表 d_program 的 id。
     * 一个 Program（演唱会）下有几百到几千个 Seat。
     */
    private Long programId;

    /**
     * 关联票档表 d_ticket_category 的 id。
     * 比如这个座位属于"内场 VIP 区"这个票档。
     */
    private Long ticketCategoryId;

    /**
     * 座位排号。比如 3 排。
     */
    private Integer rowCode;

    /**
     * 座位列号。比如 12 座。
     * (rowCode=3, colCode=12) 表示第 3 排 12 号。
     */
    private Integer colCode;

    /**
     * 座位类型。详见 seatType 枚举。
     * 比如过道座、普通座、无障碍座等。
     */
    private Integer seatType;

    /**
     * 座位价格（元）。
     * 同一票档（ticketCategoryId）的座位价格相同。
     */
    private BigDecimal price;

    /**
     * 售卖状态（核心字段，高并发下经常读写）：
     * 1 = 未售卖（可买）
     * 2 = 锁定（有人正在下单，暂时不能买）
     * 3 = 已售卖（卖掉了）
     */
    private Integer sellStatus;
}
