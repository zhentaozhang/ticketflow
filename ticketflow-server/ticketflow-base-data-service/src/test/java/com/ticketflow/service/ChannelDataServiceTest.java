package com.ticketflow.service;

import com.baidu.fsg.uid.UidGenerator;
import com.ticketflow.client.ApiDataClient;
import com.ticketflow.core.SpringUtil;
import com.ticketflow.dto.AddApiDataDto;
import com.ticketflow.dto.ChannelDataAddDto;
import com.ticketflow.dto.GetChannelDataByCodeDto;
import com.ticketflow.entity.ChannelTableData;
import com.ticketflow.mapper.ChannelDataMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.vo.GetChannelDataVo;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChannelDataServiceTest {

    @Mock
    private ChannelDataMapper channelDataMapper;

    @Mock
    private UidGenerator uidGenerator;

    @Mock
    private RedisCache redisCache;

    @Mock
    private ApiDataClient apiDataClient;

    private ChannelDataService channelDataService;

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
        channelDataService = new ChannelDataService();
        ReflectionTestUtils.setField(channelDataService, "channelDataMapper", channelDataMapper);
        ReflectionTestUtils.setField(channelDataService, "uidGenerator", uidGenerator);
        ReflectionTestUtils.setField(channelDataService, "redisCache", redisCache);
        ReflectionTestUtils.setField(channelDataService, "apiDataClient", apiDataClient);
    }

    @Test
    void getByCodeShouldReturnChannelDataWhenRunning() {
        ChannelTableData channelData = new ChannelTableData();
        channelData.setCode("2");
        channelData.setName("小程序渠道");
        when(channelDataMapper.selectOne(any())).thenReturn(channelData);

        GetChannelDataByCodeDto dto = new GetChannelDataByCodeDto();
        dto.setCode("2");

        GetChannelDataVo result = channelDataService.getByCode(dto);

        assertEquals("2", result.getCode());
        assertEquals("小程序渠道", result.getName());
    }

    @Test
    void getByCodeShouldReturnEmptyVoWhenNotExist() {
        when(channelDataMapper.selectOne(any())).thenReturn(null);

        GetChannelDataByCodeDto dto = new GetChannelDataByCodeDto();
        dto.setCode("2");

        GetChannelDataVo result = channelDataService.getByCode(dto);

        assertNull(result.getCode());
    }

    @Test
    void addShouldInsertChannelAndWriteRedisCache() {
        when(uidGenerator.getUid()).thenReturn(100L);
        ChannelDataAddDto dto = new ChannelDataAddDto();
        dto.setCode("2");
        dto.setName("小程序渠道");

        channelDataService.add(dto);

        verify(channelDataMapper).insert(any(ChannelTableData.class));
        verify(redisCache).set(any(), any(GetChannelDataVo.class));
    }

    @Test
    void testShouldRecordApiDataAndPropagateExceptionWhenCodeIsTwo() {
        when(uidGenerator.getUid()).thenReturn(100L);
        ChannelDataAddDto dto = new ChannelDataAddDto();
        dto.setCode("2");

        assertThrows(RuntimeException.class, () -> channelDataService.test(dto));

        verify(apiDataClient).add(any(AddApiDataDto.class));
    }

    @Test
    void testShouldSucceedWhenCodeNotTwo() {
        when(uidGenerator.getUid()).thenReturn(100L);
        ChannelDataAddDto dto = new ChannelDataAddDto();
        dto.setCode("3");

        channelDataService.test(dto);

        verify(apiDataClient).add(any(AddApiDataDto.class));
    }
}
