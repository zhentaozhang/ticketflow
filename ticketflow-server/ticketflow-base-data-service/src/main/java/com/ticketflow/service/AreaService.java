package com.ticketflow.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.dto.AreaGetDto;
import com.ticketflow.dto.AreaSelectDto;
import com.ticketflow.entity.Area;
import com.ticketflow.enums.AreaType;
import com.ticketflow.enums.BusinessStatus;
import com.ticketflow.mapper.AreaMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.vo.AreaVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 地区数据服务——省/市/区三级联动及经纬度查询。
 * <p>
 * 数据缓存在 Redis 中，
 * 支持根据父级 ID 获取子级地区列表
 */
@Slf4j
@Service
public class AreaService extends ServiceImpl<AreaMapper, Area> {

    @Autowired
    private AreaMapper areaMapper;

    @Autowired
    private RedisCache redisCache;

    public List<AreaVo> selectCityData() {
        List<AreaVo> areaVos = redisCache.rangeForList(RedisKeyBuild.createRedisKey(RedisKeyManage.AREA_PROVINCE_LIST),
                0, -1, AreaVo.class);
        if (CollectionUtil.isEmpty(areaVos)) {
            final LambdaQueryWrapper<Area> lambdaQueryWrapper = Wrappers.lambdaQuery(Area.class)
                    .eq(Area::getType, AreaType.MUNICIPALITIES.getCode())
                    .or(wrapper -> wrapper
                            .eq(Area::getType, AreaType.PROVINCE.getCode())
                            .eq(Area::getMunicipality, BusinessStatus.YES.getCode()));
            List<Area> areas = areaMapper.selectList(lambdaQueryWrapper);
            areaVos = BeanUtil.copyToList(areas, AreaVo.class);
            if (CollectionUtil.isNotEmpty(areaVos)) {
                redisCache.leftPushAllForList(RedisKeyBuild.createRedisKey(RedisKeyManage.AREA_PROVINCE_LIST), areaVos);
            }
        }
        return areaVos;
    }

    public List<AreaVo> selectByIdList(AreaSelectDto areaSelectDto) {
        final LambdaQueryWrapper<Area> lambdaQueryWrapper = Wrappers.lambdaQuery(Area.class)
                .in(Area::getId, areaSelectDto.getIdList());
        List<Area> areas = areaMapper.selectList(lambdaQueryWrapper);
        return BeanUtil.copyToList(areas, AreaVo.class);
    }

    public AreaVo getById(AreaGetDto areaGetDto) {
        log.info("基础服务调用 getById:{}", JSON.toJSONString(areaGetDto));
        final LambdaQueryWrapper<Area> lambdaQueryWrapper = Wrappers.lambdaQuery(Area.class)
                .eq(Area::getId, areaGetDto.getId());
        Area area = areaMapper.selectOne(lambdaQueryWrapper);
        AreaVo areaVo = new AreaVo();
        if (Objects.nonNull(area)) {
            BeanUtil.copyProperties(area, areaVo);
        }
        return areaVo;
    }

    public AreaVo current() {
        final LambdaQueryWrapper<Area> lambdaQueryWrapper = Wrappers.lambdaQuery(Area.class)
                .eq(Area::getId, 2);
        Area area = areaMapper.selectOne(lambdaQueryWrapper);
        AreaVo areaVo = new AreaVo();
        if (Objects.nonNull(area)) {
            BeanUtil.copyProperties(area, areaVo);
        }
        return areaVo;
    }

    public List<AreaVo> manageList() {
        return Stream.of(current()).toList();
    }

    public List<AreaVo> hot() {
        final LambdaQueryWrapper<Area> lambdaQueryWrapper = Wrappers.lambdaQuery(Area.class)
                .in(Area::getName, "全国", "北京", "上海", "深圳", "广州", "杭州", "天津", "重庆", "成都", "中国香港");
        List<Area> areas = areaMapper.selectList(lambdaQueryWrapper);
        return BeanUtil.copyToList(areas, AreaVo.class);
    }
}
