package com.ticketflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticketflow.dto.TicketCategoryCountDto;
import com.ticketflow.entity.TicketCategory;
import com.ticketflow.entity.TicketCategoryAggregate;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 票档表 Mapper 接口。
 *
 * 除了继承 BaseMapper 的基础 CRUD 外，这个接口重点关注 4 个自定义 SQL：
 * 1. selectAggregateList  — 统计每个节目最低价~最高价（列表页展示价格区间）
 * 2. reduceRemainNumber   — 下单时扣减某票档的库存（核心！含不超卖保护）
 * 3. increaseRemainNumber — 取消订单时归还某票档的库存（不能超还）
 * 4. batchUpdateRemainNumber — 批量扣减（历史版本用，V4 已不用）
 *
 * 注意：@Param 注解必须加，否则 XML 里拿不到参数名。
 *      比如 @Param("amount") Long amount，XML 里用 #{amount} 引用。
 */
public interface TicketCategoryMapper extends BaseMapper<TicketCategory> {

    /**
     * 统计多个节目的票档价格区间（最低价 ~ 最高价）。
     * 用在节目列表页，每个卡片上显示"￥280 - ￥1280"。
     * SQL：SELECT program_id, MIN(price), MAX(price) FROM d_ticket_category GROUP BY program_id
     *
     * @param programIdList 多个节目 ID
     * @return 每个节目的价格区间
     */
    List<TicketCategoryAggregate> selectAggregateList(@Param("programIdList") List<Long> programIdList);

    /**
     * 扣减单个票档的剩余数量（下单核心操作）。
     * SQL：UPDATE d_ticket_category SET remain_number = remain_number - #{amount}
     *      WHERE id = #{id} AND remain_number >= #{amount}
     * 重点在 WHERE remain_number >= #{amount}：
     *   如果库存不够，SQL 执行 0 行（affected rows = 0），
     *   调用方判断返回值就知道库存不足，不用加锁。
     *
     * @param amount    要扣的数量
     * @param id        票档 ID
     * @param programId 节目 ID（二次校验归属）
     * @return 影响的行数（1=成功，0=库存不够）
     */
    int reduceRemainNumber(@Param("amount") Long amount,
                           @Param("id") Long id,
                           @Param("programId") Long programId);

    /**
     * 归还单个票档的剩余数量（取消订单时调用）。
     * SQL：UPDATE d_ticket_category SET remain_number = remain_number + #{amount}
     *      WHERE id = #{id} AND remain_number + #{amount} <= total_number
     * 重点在 AND remain_number + #{amount} <= total_number：
     *   防止重复取消导致超还（还回去的比原来总数还多）。
     *
     * @param amount    要归还的数量
     * @param id        票档 ID
     * @param programId 节目 ID
     * @return 影响的行数（1=成功，0=异常）
     */
    int increaseRemainNumber(@Param("amount") Long amount,
                             @Param("id") Long id,
                             @Param("programId") Long programId);

    /**
     * 批量扣减多个票档的库存（V1-V3 版本订单用，V4 已废弃）。
     * 直接 UPDATE 不改 sellStatus，没有 LOCK 中间态。
     *
     * @param ticketCategoryCountDtoList 每个票档要扣的数量
     * @param programId                  节目 ID
     * @return 总影响行数
     */
    int batchUpdateRemainNumber(@Param("ticketCategoryCountDtoList")
                                List<TicketCategoryCountDto> ticketCategoryCountDtoList,
                                @Param("programId")
                                Long programId);
}
