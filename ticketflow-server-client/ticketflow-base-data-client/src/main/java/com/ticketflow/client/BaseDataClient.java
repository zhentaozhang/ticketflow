package com.ticketflow.client;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.AreaGetDto;
import com.ticketflow.dto.AreaSelectDto;
import com.ticketflow.dto.GetChannelDataByCodeDto;
import com.ticketflow.vo.AreaVo;
import com.ticketflow.vo.GetChannelDataVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;

import static com.ticketflow.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;

/**
 * 基础数据服务 Feign 客户端。
 * gateway 通过此接口查询渠道配置、Token TTL、地区数据
 */
@Component
@FeignClient(value = SPRING_INJECT_PREFIX_DISTINCTION_NAME + "-" + "base-data-service")
//                ↑ 服务名 = ticketflow-base-data-service  ↑ 被 gateway 和 program-service 使用
public interface BaseDataClient {
    /**
     * 根据code查询渠道配置（RSA公钥、Token密钥等）
     * gateway 验签/解密时需要渠道配置
     */
    @PostMapping("/channel/data/getByCode")
    ApiResponse<GetChannelDataVo> getByCode(GetChannelDataByCodeDto dto);

    /**
     * 根据id集合批量查询地区名
     * program-service 查节目列表时用来填充 areaName
     */
    @PostMapping(value = "/area/selectByIdList")
    ApiResponse<List<AreaVo>> selectByIdList(AreaSelectDto dto);

    /**
     * 根据id查单个地区
     */
    @PostMapping(value = "/area/getById")
    ApiResponse<AreaVo> getById(AreaGetDto dto);
}
