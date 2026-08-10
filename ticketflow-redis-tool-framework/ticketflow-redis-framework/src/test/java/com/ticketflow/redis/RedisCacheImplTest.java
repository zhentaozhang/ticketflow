package com.ticketflow.redis;

import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.core.SpringUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisCacheImpl 测试。针对框架层 Bug 修复的回归覆盖：
 * A1 getValueIsList(5参) 缓存命中返回 null
 * A2 getKeys 双重 multiGet
 * A3 leftPushForList/rightPushForList pivot 变量错位
 */
class RedisCacheImplTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ListOperations<String, String> listOperations;
    private RedisCacheImpl redisCache;
    private MockedStatic<SpringUtil> springUtilMockedStatic;

    private static final String TEST_KEY = "tf-all_rule_hash";

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        listOperations = mock(ListOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        redisCache = new RedisCacheImpl(redisTemplate);
        springUtilMockedStatic = mockStatic(SpringUtil.class);
        springUtilMockedStatic.when(SpringUtil::getPrefixDistinctionName).thenReturn("tf");
    }

    @AfterEach
    void tearDown() {
        springUtilMockedStatic.close();
    }

    @Test
    void getValueIsList_hit_returnsParsedCacheAndSkipsSupplier() {
        when(valueOperations.get(TEST_KEY)).thenReturn("[{\"id\":1},{\"id\":2}]");

        List<TestCacheDto> result = redisCache.getValueIsList(
                RedisKeyBuild.createRedisKey(RedisKeyManage.ALL_RULE_HASH),
                TestCacheDto.class,
                () -> Collections.singletonList(new TestCacheDto(99L)),
                10L, TimeUnit.SECONDS);

        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getId());
        assertEquals(2L, result.get(1).getId());
        verify(valueOperations, never()).set(any(String.class), any(String.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void getValueIsList_miss_executesSupplierAndBackfillsCache() {
        when(valueOperations.get(TEST_KEY)).thenReturn(null);
        TestCacheDto dto = new TestCacheDto(1L);

        List<TestCacheDto> result = redisCache.getValueIsList(
                RedisKeyBuild.createRedisKey(RedisKeyManage.ALL_RULE_HASH),
                TestCacheDto.class,
                () -> Collections.singletonList(dto),
                10L, TimeUnit.SECONDS);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
        verify(valueOperations).set(TEST_KEY, "[{\"id\":1}]", 10L, TimeUnit.SECONDS);
    }

    @Test
    void getValueIsList_supplierReturnsEmpty_returnsNullWithoutBackfill() {
        when(valueOperations.get(TEST_KEY)).thenReturn(null);

        List<TestCacheDto> result = redisCache.getValueIsList(
                RedisKeyBuild.createRedisKey(RedisKeyManage.ALL_RULE_HASH),
                TestCacheDto.class,
                ArrayList::new,
                10L, TimeUnit.SECONDS);

        assertNull(result);
        verify(valueOperations, never()).set(any(String.class), any(String.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void getKeys_singleMultiGetWithOptimize() {
        List<RedisKeyBuild> keyList = List.of(
                RedisKeyBuild.createRedisKey(RedisKeyManage.ALL_RULE_HASH));
        when(valueOperations.multiGet(anyList())).thenReturn(List.of("v1", "v2"));

        List<String> result = redisCache.getKeys(keyList);

        assertEquals(List.of("v1", "v2"), result);
        verify(valueOperations).multiGet(List.of(TEST_KEY));
    }

    @Test
    void leftPushForList_stringPivotAndValue_passedThrough() {
        RedisKeyBuild keyBuild = RedisKeyBuild.createRedisKey(RedisKeyManage.ALL_RULE_HASH);
        when(listOperations.leftPush(TEST_KEY, "pivot", "value")).thenReturn(1L);

        Long result = redisCache.leftPushForList(keyBuild, "pivot", "value");

        assertEquals(1L, result);
        verify(listOperations).leftPush(TEST_KEY, "pivot", "value");
    }

    @Test
    void leftPushForList_objectPivotAndValue_serializedToJson() {
        RedisKeyBuild keyBuild = RedisKeyBuild.createRedisKey(RedisKeyManage.ALL_RULE_HASH);
        TestCacheDto pivot = new TestCacheDto(1L);
        TestCacheDto value = new TestCacheDto(2L);
        when(listOperations.leftPush(eq(TEST_KEY), any(String.class), any(String.class))).thenReturn(1L);

        Long result = redisCache.leftPushForList(keyBuild, pivot, value);

        assertEquals(1L, result);
        verify(listOperations).leftPush(TEST_KEY, "{\"id\":1}", "{\"id\":2}");
    }

    @Test
    void rightPushForList_stringPivotAndValue_passedThrough() {
        RedisKeyBuild keyBuild = RedisKeyBuild.createRedisKey(RedisKeyManage.ALL_RULE_HASH);
        when(listOperations.rightPush(TEST_KEY, "pivot", "value")).thenReturn(1L);

        Long result = redisCache.rightPushForList(keyBuild, "pivot", "value");

        assertEquals(1L, result);
        verify(listOperations).rightPush(TEST_KEY, "pivot", "value");
    }

    @Test
    void rightPushForList_objectPivotAndValue_serializedToJson() {
        RedisKeyBuild keyBuild = RedisKeyBuild.createRedisKey(RedisKeyManage.ALL_RULE_HASH);
        TestCacheDto pivot = new TestCacheDto(1L);
        TestCacheDto value = new TestCacheDto(2L);
        when(listOperations.rightPush(eq(TEST_KEY), any(String.class), any(String.class))).thenReturn(1L);

        Long result = redisCache.rightPushForList(keyBuild, pivot, value);

        assertEquals(1L, result);
        verify(listOperations).rightPush(TEST_KEY, "{\"id\":1}", "{\"id\":2}");
    }

    @Test
    void removeForList_stringValue_passedThroughWithCount() {
        RedisKeyBuild keyBuild = RedisKeyBuild.createRedisKey(RedisKeyManage.ALL_RULE_HASH);
        when(listOperations.remove(TEST_KEY, 1L, "value")).thenReturn(1L);

        Long result = redisCache.removeForList(keyBuild, "value", 1L);

        assertEquals(1L, result);
        verify(listOperations).remove(TEST_KEY, 1L, "value");
    }

    @Test
    void removeForList_objectValue_serializedToJson() {
        RedisKeyBuild keyBuild = RedisKeyBuild.createRedisKey(RedisKeyManage.ALL_RULE_HASH);
        TestCacheDto value = new TestCacheDto(2L);
        when(listOperations.remove(eq(TEST_KEY), eq(0L), any(String.class))).thenReturn(1L);

        Long result = redisCache.removeForList(keyBuild, value, 0L);

        assertEquals(1L, result);
        verify(listOperations).remove(TEST_KEY, 0L, "{\"id\":2}");
    }

    static class TestCacheDto {
        private Long id;

        public TestCacheDto() {
        }

        public TestCacheDto(Long id) {
            this.id = id;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }
    }
}
