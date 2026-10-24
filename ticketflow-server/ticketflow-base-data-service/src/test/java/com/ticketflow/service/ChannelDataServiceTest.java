package com.ticketflow.service;

import com.baidu.fsg.uid.UidGenerator;
import com.ticketflow.client.ApiDataClient;
import com.ticketflow.core.SpringUtil;
import com.ticketflow.dto.AddApiDataDto;
import com.ticketflow.dto.ChannelDataAddDto;
import com.ticketflow.dto.GetChannelDataByCodeDto;
import com.ticketflow.entity.ChannelTableData;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.enums.Status;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.mapper.ChannelDataMapper;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.vo.ChannelDataPublicVo;
import com.ticketflow.vo.GetChannelDataVo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void getByCodeShouldReturnPublicVoWithoutAnySecret() {
        ChannelTableData channelData = new ChannelTableData();
        channelData.setCode("2");
        channelData.setName("小程序渠道");
        channelData.setSignPublicKey("public-key");
        channelData.setSignSecretKey("private-key-must-not-leak");
        channelData.setDataSecretKey("data-private-key-must-not-leak");
        channelData.setAesKey("aes-key-must-not-leak");
        channelData.setTokenSecret("token-secret-must-not-leak");
        when(channelDataMapper.selectOne(any())).thenReturn(channelData);

        GetChannelDataByCodeDto dto = new GetChannelDataByCodeDto();
        dto.setCode("2");

        ChannelDataPublicVo result = channelDataService.getByCode(dto);

        assertEquals("2", result.getCode());
        assertEquals("小程序渠道", result.getName());
        assertEquals("public-key", result.getSignPublicKey());
        // 公开 VO 必须不含任何私钥字段：用反射确认字段级隔离，防止将来有人把私钥加回来
        for (String forbidden : new String[]{"signSecretKey", "dataSecretKey", "aesKey", "tokenSecret"}) {
            assertTrue(hasNoField(ChannelDataPublicVo.class, forbidden), "公开 VO 不应包含字段 " + forbidden);
        }
    }

    @Test
    void getByCodeShouldReturnEmptyVoWhenNotExist() {
        when(channelDataMapper.selectOne(any())).thenReturn(null);

        GetChannelDataByCodeDto dto = new GetChannelDataByCodeDto();
        dto.setCode("2");

        ChannelDataPublicVo result = channelDataService.getByCode(dto);

        assertNull(result.getCode());
    }

    @Test
    void getSecretByCodeShouldReturnSecretFieldsForInternalCall() {
        ChannelTableData channelData = new ChannelTableData();
        channelData.setCode("2");
        channelData.setDataSecretKey("data-private-key");
        channelData.setTokenSecret("token-secret");
        when(channelDataMapper.selectOne(any())).thenReturn(channelData);

        GetChannelDataByCodeDto dto = new GetChannelDataByCodeDto();
        dto.setCode("2");

        GetChannelDataVo result = channelDataService.getSecretByCode(dto);

        assertEquals("data-private-key", result.getDataSecretKey());
        assertEquals("token-secret", result.getTokenSecret());
    }

    @Test
    void getSecretByCodeShouldThrowWhenChannelNotExistOrNotRunning() {
        when(channelDataMapper.selectOne(any())).thenReturn(null);

        GetChannelDataByCodeDto dto = new GetChannelDataByCodeDto();
        dto.setCode("2");

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> channelDataService.getSecretByCode(dto));
        assertEquals(BaseCode.CHANNEL_DATA_NOT_EXIST.getCode(), exception.getCode());
    }

    @Test
    void addShouldInsertChannelAsPendingReviewAndNotWarmCache() {
        when(uidGenerator.getUid()).thenReturn(100L);
        ChannelDataAddDto dto = new ChannelDataAddDto();
        dto.setCode("2");
        dto.setName("小程序渠道");

        channelDataService.add(dto);

        ArgumentCaptor<ChannelTableData> captor = ArgumentCaptor.forClass(ChannelTableData.class);
        verify(channelDataMapper).insert(captor.capture());
        ChannelTableData inserted = captor.getValue();
        assertEquals(100L, inserted.getId());
        // 待审核 = status 0（同时是逻辑删除标记），因此查询链路看不到它，无法立刻用于验签
        assertEquals(Status.STOP.getCode(), inserted.getStatus());
        assertNotNull(inserted.getCreateTime());
        // 未审核渠道绝不能进渠道缓存（否则等于绕过审核）
        verifyNoInteractions(redisCache);
    }

    @Test
    void approveShouldEnableChannelByRawSqlAndEvictCache() {
        when(channelDataMapper.updateStatusByCode(eq("2"), eq(Status.RUN.getCode()), any())).thenReturn(1);

        GetChannelDataByCodeDto dto = new GetChannelDataByCodeDto();
        dto.setCode("2");

        channelDataService.approve(dto);

        verify(channelDataMapper).updateStatusByCode(eq("2"), eq(Status.RUN.getCode()), any());
        // 网关侧渠道缓存必须清掉，否则撤销/轮换密钥后仍会用旧配置验签 60 分钟
        verify(redisCache).del(RedisKeyBuild.createRedisKey(RedisKeyManage.CHANNEL_DATA, "2"));
    }

    @Test
    void approveShouldThrowWhenChannelNotExist() {
        when(channelDataMapper.updateStatusByCode(eq("2"), anyInt(), any())).thenReturn(0);

        GetChannelDataByCodeDto dto = new GetChannelDataByCodeDto();
        dto.setCode("2");

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> channelDataService.approve(dto));
        assertEquals(BaseCode.CHANNEL_DATA_NOT_EXIST.getCode(), exception.getCode());
        verify(redisCache, never()).del(any(RedisKeyBuild.class));
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

    private static boolean hasNoField(Class<?> type, String fieldName) {
        try {
            type.getDeclaredField(fieldName);
            return false;
        } catch (NoSuchFieldException e) {
            return true;
        }
    }
}
