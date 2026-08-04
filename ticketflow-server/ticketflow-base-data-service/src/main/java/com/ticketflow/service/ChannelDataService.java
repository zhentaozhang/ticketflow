package com.ticketflow.service;

import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ticketflow.client.ApiDataClient;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.dto.AddApiDataDto;
import com.ticketflow.dto.ChannelDataAddDto;
import com.ticketflow.dto.GetChannelDataByCodeDto;
import com.ticketflow.entity.ChannelTableData;
import com.ticketflow.enums.Status;
import com.ticketflow.mapper.ChannelDataMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.util.DateUtils;
import com.ticketflow.vo.GetChannelDataVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 渠道数据服务——管理客户端渠道（App/Web/小程序等）的注册与查询。
 * <p>
 * 渠道注册时自动调用 customize-service 记录 API 数据，
 * 查询时优先走 Redis 缓存
 */
@Service
@Slf4j
public class ChannelDataService {

    @Autowired
    private ChannelDataMapper channelDataMapper;

    @Autowired
    private UidGenerator uidGenerator;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private ApiDataClient apiDataClient;

    public GetChannelDataVo getByCode(GetChannelDataByCodeDto dto) {
        GetChannelDataVo getChannelDataVo = new GetChannelDataVo();
        LambdaQueryWrapper<ChannelTableData> wrapper = Wrappers.lambdaQuery(ChannelTableData.class)
                .eq(ChannelTableData::getStatus, Status.RUN.getCode())
                .eq(ChannelTableData::getCode, dto.getCode());
        Optional.ofNullable(channelDataMapper.selectOne(wrapper)).ifPresent(channelData -> {
            BeanUtils.copyProperties(channelData, getChannelDataVo);
        });
        return getChannelDataVo;
    }

    @Transactional(rollbackFor = Exception.class)
    public void add(ChannelDataAddDto channelDataAddDto) {
        ChannelTableData channelData = new ChannelTableData();
        BeanUtils.copyProperties(channelDataAddDto, channelData);
        channelData.setId(uidGenerator.getUid());
        channelData.setCreateTime(DateUtils.now());
        channelDataMapper.insert(channelData);
        addRedisChannelData(channelData);
    }

    private void addRedisChannelData(ChannelTableData channelData) {
        GetChannelDataVo getChannelDataVo = new GetChannelDataVo();
        BeanUtils.copyProperties(channelData, getChannelDataVo);
        redisCache.set(RedisKeyBuild.createRedisKey(RedisKeyManage.CHANNEL_DATA, getChannelDataVo.getCode()), getChannelDataVo);
    }

    @Transactional(rollbackFor = Exception.class)
    public void test(final ChannelDataAddDto channelDataAddDto) {
        add(channelDataAddDto);
        AddApiDataDto apiDataDto = new AddApiDataDto();
        apiDataDto.setHeadVersion("1.0");
        apiDataClient.add(apiDataDto);
        if ("2".equals(channelDataAddDto.getCode())) {
            throw new RuntimeException("测试异常");
        }
    }
}
