package com.ticketflow.controller;

import com.ticketflow.common.ApiResponse;
import com.ticketflow.dto.ChannelDataAddDto;
import com.ticketflow.dto.GetChannelDataByCodeDto;
import com.ticketflow.service.ChannelDataService;
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
 */
@RestController
@RequestMapping("/channel/data")
@Tag(name = "channel-data", description = "渠道数据")
public class ChannelDataController {

    @Autowired
    private ChannelDataService channelDataService;

    @Operation(summary = "通过code查询渠道数据")
    @PostMapping(value = "/getByCode")
    public ApiResponse<GetChannelDataVo> getByCode(@Valid @RequestBody GetChannelDataByCodeDto getChannelDataByCodeDto) {
        GetChannelDataVo getChannelDataVo = channelDataService.getByCode(getChannelDataByCodeDto);
        return ApiResponse.ok(getChannelDataVo);
    }

    @Operation(summary = "添加渠道数据")
    @PostMapping(value = "/add")
    public ApiResponse<Boolean> add(@Valid @RequestBody ChannelDataAddDto channelDataAddDto) {
        channelDataService.add(channelDataAddDto);
        return ApiResponse.ok(true);
    }
}
