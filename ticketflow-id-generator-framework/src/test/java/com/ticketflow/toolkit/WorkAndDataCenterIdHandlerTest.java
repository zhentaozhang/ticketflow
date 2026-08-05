package com.ticketflow.toolkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkAndDataCenterIdHandlerTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void getWorkAndDataCenterId_parsesRedisResult() {
        when(stringRedisTemplate.execute(any(), anyList(), any(Object[].class)))
                .thenReturn("{\"workId\":5,\"dataCenterId\":3}");
        WorkAndDataCenterIdHandler handler = new WorkAndDataCenterIdHandler(stringRedisTemplate);
        WorkDataCenterId result = handler.getWorkAndDataCenterId();
        assertEquals(5L, result.getWorkId());
        assertEquals(3L, result.getDataCenterId());
    }

    @Test
    void getWorkAndDataCenterId_redisFailure_returnsEmptyWithoutThrowing() {
        when(stringRedisTemplate.execute(any(), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("redis down"));
        WorkAndDataCenterIdHandler handler = new WorkAndDataCenterIdHandler(stringRedisTemplate);
        WorkDataCenterId result = handler.getWorkAndDataCenterId();
        assertNotNull(result);
        assertNull(result.getWorkId());
        assertNull(result.getDataCenterId());
    }
}
