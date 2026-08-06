package com.ticketflow.redis;

import com.alibaba.fastjson.JSON;
import com.ticketflow.util.StringUtil;
import lombok.AllArgsConstructor;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * RedisCache 接口实现。
 * 所有操作基于 FastJSON 序列化/反序列化，
 * 对外暴露 getInstance() 方法供 Lua 脚本执行使用
 */
@AllArgsConstructor
public class RedisCacheImpl implements RedisCache {

    private StringRedisTemplate redisTemplate;

    @Override
    public <T> T get(RedisKeyBuild redisKeyBuild, Class<T> clazz) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        String key = redisKeyBuild.getRelKey();
        // 如果取String类型 则直接取出返回
        String cachedValue = redisTemplate.opsForValue().get(key);
        if (String.class.isAssignableFrom(clazz)) {
            return (T) cachedValue;
        }
        return getComplex(cachedValue, clazz);
    }

    // Cache-Aside 模式：先查缓存，miss 则执行 supplier 从 DB 加载 → 回填设置 TTL
    public <T> T get(RedisKeyBuild redisKeyBuild, Class<T> clazz, Supplier<T> supplier, long ttl, TimeUnit timeUnit) {
        T t = get(redisKeyBuild, clazz);
        if (CacheUtil.isEmpty(t)) {
            t = supplier.get();    // 从 DB 加载
            if (CacheUtil.isEmpty(t)) {
                return null;       // DB 也没有 → 不缓存空值
            }
            set(redisKeyBuild, t, ttl, timeUnit);  // 回填 Redis 并设过期
        }
        return t;
    }

    @Override
    public <T> List<T> getValueIsList(RedisKeyBuild redisKeyBuild, Class<T> clazz) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        String key = redisKeyBuild.getRelKey();
        String valueStr = redisTemplate.opsForValue().get(key);
        if (StringUtil.isEmpty(valueStr)) {
            return new ArrayList<>();
        }
        return JSON.parseArray(valueStr, clazz);
    }

    @Override
    public <T> List<T> getValueIsList(RedisKeyBuild redisKeyBuild, Class<T> clazz, Supplier<List<T>> supplier, long ttl, TimeUnit timeUnit) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        String key = redisKeyBuild.getRelKey();
        String valueStr = redisTemplate.opsForValue().get(key);
        if (!CacheUtil.isEmpty(valueStr)) {
            return JSON.parseArray(valueStr, clazz);
        }
        List<T> tList = supplier.get();
        if (CacheUtil.isEmpty(tList)) {
            return null;
        }
        set(redisKeyBuild, tList, ttl, timeUnit);
        return tList;
    }


    @Override
    public List<String> getKeys(List<RedisKeyBuild> keyList) {
        CacheUtil.checkNotEmpty(keyList);
        List<String> batchKey = CacheUtil.getBatchKey(keyList);

        return CacheUtil.optimizeRedisList(redisTemplate.opsForValue().multiGet(batchKey));
    }

    @Override
    public Boolean hasKey(RedisKeyBuild redisKeyBuild) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        String key = redisKeyBuild.getRelKey();
        return redisTemplate.hasKey(key);
    }

    @Override
    public Long getExpire(RedisKeyBuild redisKeyBuild) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        String key = redisKeyBuild.getRelKey();
        return redisTemplate.getExpire(key);
    }

    @Override
    public Long getExpire(RedisKeyBuild redisKeyBuild, TimeUnit timeUnit) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        String key = redisKeyBuild.getRelKey();
        return redisTemplate.getExpire(key, timeUnit);
    }


    @Override
    public void set(RedisKeyBuild redisKeyBuild, Object object) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        String key = redisKeyBuild.getRelKey();
        String json = object instanceof String ? (String) object : JSON.toJSONString(object);
        redisTemplate.opsForValue().set(key, json);
    }

    @Override
    public void set(RedisKeyBuild redisKeyBuild, Object object, long ttl) {
        set(redisKeyBuild, object, ttl, CacheUtil.DEFAULT_TIME_UNIT);
    }

    @Override
    public void set(RedisKeyBuild redisKeyBuild, Object object, long ttl, TimeUnit timeUnit) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        String key = redisKeyBuild.getRelKey();
        String json = object instanceof String ? (String) object : JSON.toJSONString(object);
        redisTemplate.opsForValue().set(key, json, ttl, timeUnit);
    }


    @Override
    public Long incrBy(RedisKeyBuild redisKeyBuild, long increment) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        String key = redisKeyBuild.getRelKey();
        return redisTemplate.opsForValue().increment(key, increment);
    }


    @Override
    public void putHash(RedisKeyBuild redisKeyBuild, String hashKey, Object value) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        String key = redisKeyBuild.getRelKey();
        String jsonValue = value instanceof String ? (String) value : JSON.toJSONString(value);
        redisTemplate.opsForHash().put(key, hashKey, jsonValue);
    }

    @Override
    public void putHash(RedisKeyBuild redisKeyBuild, String hashKey, Object value, long ttl) {
        putHash(redisKeyBuild, hashKey, value, ttl, CacheUtil.DEFAULT_TIME_UNIT);
    }

    @Override
    public void putHash(RedisKeyBuild redisKeyBuild, String hashKey, Object value, long ttl, TimeUnit timeUnit) {
        putHash(redisKeyBuild, hashKey, value);
        // 设置过期时间
        expire(redisKeyBuild, ttl, timeUnit);
    }

    @Override
    public void putHash(RedisKeyBuild redisKeyBuild, Map<String, ?> map) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        String key = redisKeyBuild.getRelKey();
        Map<String, String> mapForSave = new HashMap<>(map.size());
        map.forEach((hashKey, val) -> {
            String jsonValue = val instanceof String ? (String) val : JSON.toJSONString(val);
            mapForSave.put(hashKey, jsonValue);
        });
        redisTemplate.opsForHash().putAll(key, mapForSave);
    }

    @Override
    public void putHash(RedisKeyBuild redisKeyBuild, Map<String, ?> map, long ttl) {
        putHash(redisKeyBuild, map, ttl, CacheUtil.DEFAULT_TIME_UNIT);
    }

    @Override
    public void putHash(RedisKeyBuild redisKeyBuild, Map<String, ?> map, long ttl, TimeUnit timeUnit) {
        putHash(redisKeyBuild, map);
        expire(redisKeyBuild, ttl, timeUnit);
    }

    @Override
    public <T> T getForHash(RedisKeyBuild redisKeyBuild, String hashKey, Class<T> clazz) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        CacheUtil.checkNotBlank(hashKey);
        String key = redisKeyBuild.getRelKey();
        Object o = redisTemplate.opsForHash().get(key, hashKey);
        // 如果取String类型 则直接取出返回
        if (String.class.isAssignableFrom(clazz)) {
            return (T) o;
        }
        return getComplex(o, clazz);
    }

    @Override
    public <T> List<T> multiGetForHash(RedisKeyBuild redisKeyBuild, List<String> hashKeys, Class<T> clazz) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        CacheUtil.checkNotBlank(hashKeys);
        String key = redisKeyBuild.getRelKey();
        List<Object> objHashKeys = new ArrayList<>(hashKeys);
        List<Object> multiGetObj = redisTemplate.opsForHash().multiGet(key, objHashKeys);

        if (CacheUtil.checkRedisListIsEmpty(multiGetObj)) {
            return new ArrayList<>();
        }
        if (String.class.isAssignableFrom(clazz)) {
            return (List<T>) multiGetObj;
        }

        return parseObjects(multiGetObj, clazz);
    }

    @Override
    public <T> Map<String, T> getAllMapForHash(RedisKeyBuild redisKeyBuild, Class<T> clazz) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        String key = redisKeyBuild.getRelKey();
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        Map<String, T> map = new HashMap<>(64);
        entries.forEach((k, v) -> {
            map.put(String.valueOf(k), getComplex(v, clazz));
        });
        return map;
    }

    @Override
    public Long leftPushForList(RedisKeyBuild redisKeyBuild, Object value) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        CacheUtil.checkNotEmpty(value);
        String key = redisKeyBuild.getRelKey();
        String jsonValue = value instanceof String ? (String) value : JSON.toJSONString(value);
        return redisTemplate.opsForList().leftPush(key, jsonValue);
    }

    @Override
    public Long leftPushAllForList(RedisKeyBuild redisKeyBuild, List<?> valueList) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        CacheUtil.checkNotEmpty(valueList);
        String key = redisKeyBuild.getRelKey();
        List<String> jsonList = new ArrayList<>(valueList.size());
        valueList.forEach(value -> {
            String jsonValue = value instanceof String ? (String) value : JSON.toJSONString(value);
            jsonList.add(jsonValue);
        });
        return redisTemplate.opsForList().leftPushAll(key, jsonList);
    }

    @Override
    public Long leftPushForList(RedisKeyBuild redisKeyBuild, Object pivot, Object value) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        CacheUtil.checkNotEmpty(pivot);
        CacheUtil.checkNotEmpty(value);
        String key = redisKeyBuild.getRelKey();
        String jsonPivot = pivot instanceof String ? (String) pivot : JSON.toJSONString(pivot);
        String jsonValue = value instanceof String ? (String) value : JSON.toJSONString(value);
        return redisTemplate.opsForList().leftPush(key, jsonPivot, jsonValue);
    }

    @Override
    public Long rightPushForList(RedisKeyBuild redisKeyBuild, Object value) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        CacheUtil.checkNotEmpty(value);
        String key = redisKeyBuild.getRelKey();
        String jsonValue = value instanceof String ? (String) value : JSON.toJSONString(value);
        return redisTemplate.opsForList().rightPush(key, jsonValue);
    }

    @Override
    public Long rightPushForList(RedisKeyBuild redisKeyBuild, Object pivot, Object value) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        CacheUtil.checkNotEmpty(pivot);
        CacheUtil.checkNotEmpty(value);
        String key = redisKeyBuild.getRelKey();
        String jsonPivot = pivot instanceof String ? (String) pivot : JSON.toJSONString(pivot);
        String jsonValue = value instanceof String ? (String) value : JSON.toJSONString(value);
        return redisTemplate.opsForList().rightPush(key, jsonPivot, jsonValue);
    }

    @Override
    public <T> List<T> rangeForList(RedisKeyBuild redisKeyBuild, long start, long end, Class<T> clazz) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        String key = redisKeyBuild.getRelKey();
        List range = redisTemplate.opsForList().range(key, start, end);
        if (CacheUtil.checkRedisListIsEmpty(range)) {
            return new ArrayList<>();
        }
        return parseObjects(range, clazz);
    }

    @Override
    public Long lenForList(RedisKeyBuild redisKeyBuild) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        String key = redisKeyBuild.getRelKey();
        return redisTemplate.opsForList().size(key);
    }

    @Override
    public void del(RedisKeyBuild redisKeyBuild) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        String key = redisKeyBuild.getRelKey();
        redisTemplate.delete(key);
    }


    @Override
    public Long delForHash(RedisKeyBuild redisKeyBuild, String hashKey) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        CacheUtil.checkNotBlank(hashKey);
        String key = redisKeyBuild.getRelKey();
        return redisTemplate.opsForHash().delete(key, hashKey);
    }

    @Override
    public Long delForHash(RedisKeyBuild redisKeyBuild, Collection<String> hashKeys) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        CacheUtil.checkNotBlank(hashKeys);
        String key = redisKeyBuild.getRelKey();
        return redisTemplate.opsForHash().delete(key, hashKeys.toArray());
    }

    @Override
    public void del(Collection<RedisKeyBuild> keys) {
        CacheUtil.checkNotEmpty(keys);
        List<String> batchKey = CacheUtil.getBatchKey(keys);
        redisTemplate.delete(batchKey);
    }

    @Override
    public Boolean expire(RedisKeyBuild redisKeyBuild, long ttl, TimeUnit timeUnit) {
        CacheUtil.checkNotBlank(redisKeyBuild);
        String key = redisKeyBuild.getRelKey();
        return redisTemplate.expire(key, ttl, timeUnit);
    }

    // 暴露底层 StringRedisTemplate，供 Lua 脚本通过 execute(redisScript, keys, args) 调用
    public RedisTemplate getInstance() {
        return redisTemplate;
    }


    public <T> T getComplex(Object source, Class<T> clazz) {
        if (source == null) {
            return null;
        }
        if (clazz.isAssignableFrom(String.class)) {
            if (source instanceof String) {
                return (T) source;
            } else {
                return (T) JSON.toJSONString(source);
            }
        }
        return source instanceof String ? JSON.parseObject((String) source, CacheUtil.buildType(clazz)) : null;
    }

    public <T> List<T> parseObjects(List<Object> sources, Class<T> clazz) {
        if (sources == null) {
            return new ArrayList<>();
        }
        if (clazz.isAssignableFrom(String.class)) {
            List<T> resultList = (List<T>) sources.stream()
                    .filter(Objects::nonNull)
                    .map(each -> each instanceof String ? (String) each : JSON.toJSONString(each))
                    .collect(Collectors.toList());
            return resultList;
        }
        List<T> resultList = (List<T>) sources.stream()
                .filter(Objects::nonNull)
                .map(each -> each instanceof String ? JSON.parseObject((String) each, CacheUtil.buildType(clazz)) : null)
                .collect(Collectors.toList());
        return resultList;
    }

    public <T> Set<T> parseObjects(Set<Object> sources, Class<T> clazz) {
        if (sources == null) {
            return new HashSet<>();
        }
        if (clazz.isAssignableFrom(String.class)) {
            Set<T> resultSet = (Set<T>) sources.stream()
                    .map(each -> each instanceof String ? (String) each : JSON.toJSONString(each))
                    .collect(Collectors.toSet());
            return resultSet;
        }
        Set<T> resultSet = (Set<T>) sources.stream()
                .map(each -> each instanceof String ? JSON.parseObject((String) each, CacheUtil.buildType(clazz)) : null)
                .collect(Collectors.toSet());
        return resultSet;
    }

    public <T> Set<ZSetOperations.TypedTuple<T>> typedTupleStringParseObjects(Set<ZSetOperations.TypedTuple<String>> sources, Class<T> clazz) {
        if (sources == null) {
            return new HashSet<>();
        }
        Set<ZSetOperations.TypedTuple<T>> set = new HashSet<>(sources.size());
        for (ZSetOperations.TypedTuple<String> typedTuple : sources) {
            String value = typedTuple.getValue();
            T complex = getComplex(value, clazz);
            Double score = typedTuple.getScore();
            DefaultTypedTuple defaultTypedTuple = new DefaultTypedTuple(complex, score);
            set.add(defaultTypedTuple);
        }
        return set;
    }
}
