package com.ticketflow.controller;

import com.ticketflow.annotation.InternalApi;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.ChannelDataAddDto;
import com.ticketflow.dto.GetChannelDataByCodeDto;
import com.ticketflow.service.ChannelDataService;
import com.ticketflow.vo.ChannelDataPublicVo;
import com.ticketflow.vo.GetChannelDataVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 渠道 API——客户端渠道注册与查询
 * <p>
 * 密钥治理（P0-5）：
 * <ul>
 *   <li>{@code /getByCode}：公开，只返回不含私钥的 {@link ChannelDataPublicVo}；</li>
 *   <li>{@code /getSecretByCode}：内部，返回含 RSA/AES 私钥与 tokenSecret 的完整配置；</li>
 *   <li>{@code /add}、{@code /approve}：内部，登记需人工审核，审核通过才参与验签。</li>
 * </ul>
 * 内部接口由 {@link InternalApi} + InternalApiInterceptor 校验内部令牌。
 */
@RestController
@RequestMapping("/channel/data")
@Tag(name = "channel-data", description = "渠道数据")
public class ChannelDataController {

    @Autowired
    private ChannelDataService channelDataService;

    /**
     * 公开查询：只返回公钥级别的渠道信息，任何私钥都不会从这里出去。
     */
    @Operation(summary = "通过code查询渠道公开数据（不含私钥）")
    @PostMapping(value = "/getByCode")
    public ApiResponse<ChannelDataPublicVo> getByCode(@Valid @RequestBody GetChannelDataByCodeDto getChannelDataByCodeDto) {
        ChannelDataPublicVo channelDataPublicVo = channelDataService.getByCode(getChannelDataByCodeDto);
        return ApiResponse.ok(channelDataPublicVo);
    }

    /**
     * 内部查询：网关验签/解密、user-service 生成 token 需要私密配置，只能服务端调用。
     */
    @InternalApi
    @Operation(summary = "通过code查询渠道私密配置（仅内部调用）")
    @PostMapping(value = "/getSecretByCode")
    public ApiResponse<GetChannelDataVo> getSecretByCode(@Valid @RequestBody GetChannelDataByCodeDto getChannelDataByCodeDto) {
        GetChannelDataVo getChannelDataVo = channelDataService.getSecretByCode(getChannelDataByCodeDto);
        return ApiResponse.ok(getChannelDataVo);
    }

    /**
     * 登记渠道：落库为未启用，必须等人工审核。
     */
    @InternalApi
    @Operation(summary = "登记渠道（待审核，仅内部调用）")
    @PostMapping(value = "/add")
    public ApiResponse<Boolean> add(@Valid @RequestBody ChannelDataAddDto channelDataAddDto) {
        channelDataService.add(channelDataAddDto);
        return ApiResponse.ok(true);
    }

    /**
     * 审核通过：把待审核渠道置为启用并清理网关缓存。
     */
    @InternalApi
    @Operation(summary = "审核通过渠道（仅内部调用）")
    @PostMapping(value = "/approve")
    public ApiResponse<Boolean> approve(@Valid @RequestBody GetChannelDataByCodeDto getChannelDataByCodeDto) {
        channelDataService.approve(getChannelDataByCodeDto);
        return ApiResponse.ok(true);
    }
}
