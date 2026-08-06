package com.ticketflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 迁移服务通用 Mapper 抽象。
 * 基因法分片的三张订单主表（d_order / d_order_ticket_user / d_order_ticket_user_record）
 * 结构一致，迁移时都需要物理删除能力
 *
 * @param <T> 实体
 */
public interface MigrateMapper<T> extends BaseMapper<T> {

    /**
     * 物理删除
     *
     * @param ids id 列表
     * @return 删除数量
     */
    Integer physicalDeleteByIds(@Param("ids") List<Long> ids);

    /**
     * 批量幂等插入（INSERT IGNORE）。目标分片已有同主键数据时跳过，
     * 使迁移中断后重跑可自愈收敛，无需人工清理
     *
     * @param list 待插入数据
     * @return 实际插入数量
     */
    int batchInsertIgnore(@Param("list") List<T> list);
}
