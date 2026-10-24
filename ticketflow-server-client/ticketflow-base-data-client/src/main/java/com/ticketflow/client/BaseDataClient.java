package com.ticketflow.client;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.AreaGetDto;
import com.ticketflow.dto.AreaSelectDto;
import com.ticketflow.dto.GetChannelDataByCodeDto;
import com.ticketflow.vo.AreaVo;
import com.ticketflow.vo.ChannelDataPublicVo;
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
@FeignClient(value = SPRING_INJECT_PREFIX_DISTINCTION_NAME + "-" + "base-data-service",
        contextId = "baseDataClient")
//                ↑ 服务名 = ticketflow-base-data-service  ↑ 被 gateway 和 program-service 使用
//                显式 contextId（固定字符串）→ feign.client.config.baseDataClient.* 超时配置与前缀解耦
public interface BaseDataClient {
    /**
     * 根据code查询渠道配置（RSA公钥、Token密钥等）
     * gateway 验签/解密时需要渠道配置
     */
    @PostMapping("/channel/data/getByCode")
    ApiResponse<ChannelDataPublicVo> getByCode(GetChannelDataByCodeDto dto);

    /**
     * 根据code查询渠道私密配置（含 RSA/AES 私钥、tokenSecret）。
     * <p>
     * 仅供服务端进程调用：目标接口由 @InternalApi 保护，需要携带内部令牌头
     * （本模块的 {@link com.ticketflow.feign.InternalTokenFeignInterceptor} 只对 base-data-service 自动附加）。
     * 公开接口 /channel/data/getByCode 不再返回任何私钥。
     */
    @PostMapping("/channel/data/getSecretByCode")
    ApiResponse<GetChannelDataVo> getSecretByCode(GetChannelDataByCodeDto dto);

    /**
     * 审核通过渠道（把待审核渠道置为启用，并清掉网关侧的渠道缓存）。
     * 仅内部运维调用，@InternalApi 保护。
     */
    @PostMapping("/channel/data/approve")
    ApiResponse<Boolean> approve(GetChannelDataByCodeDto dto);

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
