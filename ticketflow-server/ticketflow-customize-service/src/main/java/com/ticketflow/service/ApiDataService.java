package com.ticketflow.service;

import cn.hutool.core.collection.CollUtil;
import com.alibaba.fastjson.JSON;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ticketflow.core.RepeatExecuteLimitConstants;
import com.ticketflow.dto.AddApiDataDto;
import com.ticketflow.util.StringUtil;
import com.ticketflow.dto.ApiDataDto;
import com.ticketflow.entity.ApiData;
import com.ticketflow.mapper.ApiDataMapper;
import com.ticketflow.repeatexecutelimit.annotion.RepeatExecuteLimit;
import com.ticketflow.vo.ApiDataVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * API 调用记录服务——记录每次外部 API 调用的请求/响应/状态。
 * <p>
 * 带 @RepeatExecuteLimit 防重幂等，
 * 支持分页查询调用历史
 */
@Slf4j
@Service
public class ApiDataService extends ServiceImpl<ApiDataMapper, ApiData> {

    @Autowired
    private ApiDataMapper apiDataMapper;

    @Autowired
    private UidGenerator uidGenerator;

    @RepeatExecuteLimit(name = RepeatExecuteLimitConstants.CONSUMER_API_DATA_MESSAGE, keys = {"#apiData.id"}, durationTime = 60)
    public void saveApiData(ApiData apiData) {
        ApiData dbApiData = apiDataMapper.selectById(apiData.getId());
        if (Objects.isNull(dbApiData)) {
            log.info("saveApiData apiData:{}", JSON.toJSONString(apiData));
            apiDataMapper.insert(apiData);
        }
    }

    public Page<ApiDataVo> pageList(final ApiDataDto dto) {
        Page<ApiData> page = Page.of(dto.getPageNo(), dto.getPageSize());
        LambdaQueryWrapper<ApiData> queryWrapper = Wrappers.lambdaQuery(ApiData.class)
                .eq(StringUtil.isNotEmpty(dto.getApiAddress()), ApiData::getApiAddress, dto.getApiAddress())
                .eq(StringUtil.isNotEmpty(dto.getApiUrl()), ApiData::getApiUrl, dto.getApiUrl())
                .ge(Objects.nonNull(dto.getStartDate()), ApiData::getCreateTime, dto.getStartDate())
                .le(Objects.nonNull(dto.getEndDate()), ApiData::getCreateTime, dto.getEndDate());
        Page<ApiData> apiDataPage = apiDataMapper.selectPage(page, queryWrapper);
        List<ApiData> apiDataList = apiDataPage.getRecords();
        Page<ApiDataVo> apiDataPageVo = new Page<>();
        BeanUtils.copyProperties(apiDataPage, apiDataPageVo);
        List<ApiDataVo> apiDataVoList = new ArrayList<>();
        if (CollUtil.isNotEmpty(apiDataList)) {
            apiDataVoList = apiDataList.stream().map(apiData -> {
                ApiDataVo apiDataVo = new ApiDataVo();
                BeanUtils.copyProperties(apiData, apiDataVo);
                return apiDataVo;
            }).collect(Collectors.toList());
        }
        apiDataPageVo.setRecords(apiDataVoList);
        return apiDataPageVo;
    }

    public Boolean add(final AddApiDataDto dto) {
        ApiData apiData = new ApiData();
        BeanUtils.copyProperties(dto, apiData);
        apiData.setId(uidGenerator.getUid());
        apiDataMapper.insert(apiData);
        return true;
    }
}
