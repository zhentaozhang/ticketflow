package com.ticketflow.service;

import com.ticketflow.core.SpringUtil;
import com.ticketflow.dto.AreaGetDto;
import com.ticketflow.dto.AreaSelectDto;
import com.ticketflow.entity.Area;
import com.ticketflow.mapper.AreaMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.vo.AreaVo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AreaServiceTest {

    @Mock
    private AreaMapper areaMapper;

    @Mock
    private RedisCache redisCache;

    private AreaService areaService;

    @BeforeAll
    static void initSpringUtil() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        when(context.getEnvironment()).thenReturn(mock(ConfigurableEnvironment.class));
        new SpringUtil().initialize(context);
    }

    @AfterAll
    static void clearSpringUtil() {
        new SpringUtil().initialize(null);
    }

    @BeforeEach
    void setUp() {
        areaService = new AreaService();
        ReflectionTestUtils.setField(areaService, "areaMapper", areaMapper);
        ReflectionTestUtils.setField(areaService, "redisCache", redisCache);
    }

    @Test
    void selectCityDataShouldReturnCachedDataWhenPresent() {
        AreaVo cached = new AreaVo();
        cached.setName("北京");
        when(redisCache.rangeForList(any(), eq(0L), eq(-1L), eq(AreaVo.class)))
                .thenReturn(List.of(cached));

        List<AreaVo> result = areaService.selectCityData();

        assertEquals(1, result.size());
        assertEquals("北京", result.get(0).getName());
        verifyNoInteractions(areaMapper);
    }

    @Test
    void selectCityDataShouldQueryDbAndBackfillCacheWhenMiss() {
        when(redisCache.rangeForList(any(), eq(0L), eq(-1L), eq(AreaVo.class))).thenReturn(null);
        Area area = new Area();
        area.setName("北京");
        when(areaMapper.selectList(any())).thenReturn(List.of(area));

        List<AreaVo> result = areaService.selectCityData();

        assertEquals(1, result.size());
        assertEquals("北京", result.get(0).getName());
        verify(redisCache).leftPushAllForList(any(), any());
    }

    @Test
    void selectCityDataShouldNotBackfillWhenDbEmpty() {
        when(redisCache.rangeForList(any(), eq(0L), eq(-1L), eq(AreaVo.class))).thenReturn(null);
        when(areaMapper.selectList(any())).thenReturn(List.of());

        List<AreaVo> result = areaService.selectCityData();

        assertEquals(0, result.size());
        verify(redisCache, never()).leftPushAllForList(any(), any());
    }

    @Test
    void selectByIdListShouldConvertAreas() {
        Area area = new Area();
        area.setId(1L);
        area.setName("北京");
        when(areaMapper.selectList(any())).thenReturn(List.of(area));
        AreaSelectDto dto = new AreaSelectDto();
        dto.setIdList(List.of(1L));

        List<AreaVo> result = areaService.selectByIdList(dto);

        assertEquals(1, result.size());
        assertEquals("北京", result.get(0).getName());
    }

    @Test
    void getByIdShouldReturnAreaVoWhenExist() {
        Area area = new Area();
        area.setName("上海");
        when(areaMapper.selectOne(any())).thenReturn(area);
        AreaGetDto dto = new AreaGetDto();
        dto.setId(3L);

        AreaVo result = areaService.getById(dto);

        assertNotNull(result);
        assertEquals("上海", result.getName());
    }

    @Test
    void getByIdShouldReturnEmptyVoWhenNotExist() {
        when(areaMapper.selectOne(any())).thenReturn(null);
        AreaGetDto dto = new AreaGetDto();
        dto.setId(3L);

        AreaVo result = areaService.getById(dto);

        assertNull(result.getName());
    }

    @Test
    void currentShouldReturnArea() {
        Area area = new Area();
        area.setName("北京");
        when(areaMapper.selectOne(any())).thenReturn(area);

        AreaVo result = areaService.current();

        assertEquals("北京", result.getName());
    }

    @Test
    void hotShouldReturnAreas() {
        Area area = new Area();
        area.setName("北京");
        when(areaMapper.selectList(any())).thenReturn(List.of(area));

        List<AreaVo> result = areaService.hot();

        assertEquals(1, result.size());
        assertEquals("北京", result.get(0).getName());
    }
}
