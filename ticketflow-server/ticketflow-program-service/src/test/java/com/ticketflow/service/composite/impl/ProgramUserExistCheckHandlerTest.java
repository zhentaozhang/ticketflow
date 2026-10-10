package com.ticketflow.service.composite.impl;

import com.ticketflow.client.UserClient;
import com.ticketflow.common.ApiResponse;
import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.core.SpringUtil;
import com.ticketflow.dto.ProgramOrderCreateDto;
import com.ticketflow.dto.TicketUserListDto;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.redis.RedisCache;
import com.ticketflow.redis.RedisKeyBuild;
import com.ticketflow.service.tool.TokenExpireManager;
import com.ticketflow.vo.TicketUserVo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProgramUserExistCheckHandler 用户有效性校验测试：
 * 1. 缓存 miss → RPC 成功后写回 TICKET_USER_LIST（带 TTL 与登录态对齐）
 * 2. 空列表不写回（避免缓存"无购票人"导致新增购票人后 TTL 内校验失败）
 * 3. 缓存命中不触发 RPC
 * 4. 写回失败不影响下单校验
 */
class ProgramUserExistCheckHandlerTest {

    private ProgramUserExistCheckHandler handler;
    private UserClient userClient;
    private RedisCache redisCache;
    private TokenExpireManager tokenExpireManager;

    private static final Long USER_ID = 1L;
    private static final Long PROGRAM_ID = 10L;
    private static final Long TICKET_USER_ID = 100L;

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
        handler = new ProgramUserExistCheckHandler();
        userClient = mock(UserClient.class);
        redisCache = mock(RedisCache.class);
        tokenExpireManager = mock(TokenExpireManager.class);

        ReflectionTestUtils.setField(handler, "userClient", userClient);
        ReflectionTestUtils.setField(handler, "redisCache", redisCache);
        ReflectionTestUtils.setField(handler, "tokenExpireManager", tokenExpireManager);

        when(tokenExpireManager.getTokenExpireTime()).thenReturn(30L);
    }

    private ProgramOrderCreateDto buildCreateDto() {
        ProgramOrderCreateDto dto = new ProgramOrderCreateDto();
        dto.setUserId(USER_ID);
        dto.setProgramId(PROGRAM_ID);
        dto.setTicketUserIdList(List.of());
        return dto;
    }

    private TicketUserVo buildTicketUserVo() {
        TicketUserVo vo = new TicketUserVo();
        vo.setId(TICKET_USER_ID);
        return vo;
    }

    @Test
    void 缓存miss时RPC结果写回缓存带TTL与登录态对齐() {
        when(redisCache.getValueIsList(any(), eq(TicketUserVo.class))).thenReturn(null);
        when(userClient.list(any(TicketUserListDto.class))).thenReturn(ApiResponse.ok(List.of(buildTicketUserVo())));

        handler.execute(buildCreateDto());

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(redisCache).set(eq(RedisKeyBuild.createRedisKey(RedisKeyManage.TICKET_USER_LIST, USER_ID)),
                captor.capture(), eq(31L), eq(TimeUnit.MINUTES));
        assertEquals(1, captor.getValue().size());
    }

    @Test
    void 缓存miss且RPC返回空列表时不写回并抛异常() {
        when(redisCache.getValueIsList(any(), eq(TicketUserVo.class))).thenReturn(null);
        when(userClient.list(any(TicketUserListDto.class))).thenReturn(ApiResponse.ok(List.of()));

        assertThrows(TicketFlowFrameException.class, () -> handler.execute(buildCreateDto()));

        verify(redisCache, never()).set(any(RedisKeyBuild.class), any(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void 缓存命中时不触发RPC() {
        when(redisCache.getValueIsList(any(), eq(TicketUserVo.class))).thenReturn(List.of(buildTicketUserVo()));

        handler.execute(buildCreateDto());

        verify(userClient, never()).list(any(TicketUserListDto.class));
    }

    @Test
    void 写回缓存失败不影响下单校验() {
        when(redisCache.getValueIsList(any(), eq(TicketUserVo.class))).thenReturn(null);
        when(userClient.list(any(TicketUserListDto.class))).thenReturn(ApiResponse.ok(List.of(buildTicketUserVo())));
        doThrow(new RuntimeException("redis异常")).when(redisCache).set(any(RedisKeyBuild.class), any(), anyLong(), any(TimeUnit.class));

        handler.execute(buildCreateDto());

        verify(userClient).list(any(TicketUserListDto.class));
    }
}
