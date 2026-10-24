package com.ticketflow.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ticketflow.entity.ChannelTableData;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

/**
 * 渠道数据表 Mapper
 */
public interface ChannelDataMapper extends BaseMapper<ChannelTableData> {

    /**
     * 审核通过渠道：把 status 从 0（待审核/禁用）改为 1（启用）。
     * <p>
     * 为什么不用 BaseMapper.update：status 是 MyBatis-Plus 的逻辑删除字段，
     * 框架会自动给 update 追加 {@code status = 1} 条件，0→1 的更新会被直接过滤掉，
     * 表现为"审核接口返回成功但渠道仍然不可用"。这里用原生 SQL 绕过逻辑删除条件。
     *
     * @param code     渠道编码
     * @param status   目标状态（1 启用）
     * @param editTime 编辑时间（原生 SQL 不会触发自动填充，需显式写入）
     * @return 受影响行数，0 表示渠道不存在
     */
    @Update("update d_channel_data set status = #{status}, edit_time = #{editTime} where code = #{code}")
    int updateStatusByCode(@Param("code") String code, @Param("status") Integer status, @Param("editTime") Date editTime);
}
