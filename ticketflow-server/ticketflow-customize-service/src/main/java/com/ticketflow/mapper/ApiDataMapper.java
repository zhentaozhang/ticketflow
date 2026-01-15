package com.ticketflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ticketflow.dto.ApiDataDto;
import com.ticketflow.entity.ApiData;
import com.ticketflow.vo.ApiDataVo;

/**
 * API 调用记录表 Mapper
 */
public interface ApiDataMapper extends BaseMapper<ApiData> {
    /**
     * 分页查询
     *
     * @param page       分页对象
     * @param apiDataDto 参数
     * @return 分页数据
     *
     */
    Page<ApiDataVo> pageList(Page<ApiData> page, ApiDataDto apiDataDto);
}
