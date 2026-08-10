package com.ticketflow.redis;

import org.springframework.data.redis.core.RedisTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redis 操作接口（覆盖 String / Hash / List / Set / ZSet 所有常用数据结构）。
 * <p>
 * 特色：
 * get(RedisKeyBuild, Class, Supplier, ttl) — Cache-Aside 模式，
 * 缓存 miss 时自动执行 Supplier 回填并设置过期时间
 * getInstance() — 暴露底层 RedisTemplate，供 Lua 脚本 execute() 使用
 * <p>
 * 所有 key 统一用 RedisKeyBuild 构建，自动追加环境前缀
 */
public interface RedisCache {

    /**
     * 获取字符串对象
     *
     * @param redisKeyBuild RedisKeyBuild
     * @param clazz         类对象
     * @param <T>           T
     * @return T 普通对象
     */
    <T> T get(RedisKeyBuild redisKeyBuild, Class<T> clazz);

    /**
     * 获取字符串对象(如果缓存中不存在，则执行给定的supplier接口)
     *
     * @param redisKeyBuild RedisKeyBuild
     * @param clazz         类对象
     * @param <T>           T
     * @param supplier      缓存为空时，执行的逻辑
     * @param ttl           过期时间
     * @param timeUnit      时间单位
     * @return T 普通对象
     */
    <T> T get(RedisKeyBuild redisKeyBuild, Class<T> clazz, Supplier<T> supplier, long ttl, TimeUnit timeUnit);


    /**
     * 获取字符串对象, 并且字符串中是集合内容
     *
     * @param redisKeyBuild 缓存key
     * @param clazz         类型
     * @param <T>           指定泛型
     * @return List<T>
     */
    <T> List<T> getValueIsList(RedisKeyBuild redisKeyBuild, Class<T> clazz);

    /**
     * 获取字符串对象, 并且字符串中是集合内容(如果缓存中不存在，则执行给定的supplier接口)
     *
     * @param redisKeyBuild 缓存key
     * @param clazz         类型
     * @param <T>           指定泛型
     * @param supplier      缓存为空时，执行的逻辑
     * @param ttl           过期时间
     * @param timeUnit      时间单位
     * @return List<T>
     */
    <T> List<T> getValueIsList(RedisKeyBuild redisKeyBuild, Class<T> clazz, Supplier<List<T>> supplier, long ttl, TimeUnit timeUnit);


    /**
     * 通过多个key批量获取多个value
     *
     * @param keyList key集合
     * @return List<String>
     */
    List<String> getKeys(List<RedisKeyBuild> keyList);

    /**
     * 判断key是否存在
     *
     * @param redisKeyBuild redisKeyBuild
     * @return 是否存在 可能为空
     */
    Boolean hasKey(RedisKeyBuild redisKeyBuild);

    /**
     * 删除key
     *
     * @param redisKeyBuild 缓存key
     * @return
     */
    void del(RedisKeyBuild redisKeyBuild);


    /**
     * 批量删除key
     *
     * @param keys key集合
     */
    void del(Collection<RedisKeyBuild> keys);

    /**
     * 设置key过期时间
     *
     * @param redisKeyBuild RedisKeyBuild
     * @param ttl           过期时间
     * @param timeUnit      时间单位
     * @return 是否成功
     */
    Boolean expire(RedisKeyBuild redisKeyBuild, long ttl, TimeUnit timeUnit);

    /**
     * 获取key超时时间
     *
     * @param redisKeyBuild redisKeyBuild
     * @return 超时时间
     */
    Long getExpire(RedisKeyBuild redisKeyBuild);

    /**
     * 获取key超时时间
     *
     * @param redisKeyBuild redisKeyBuild
     * @param timeUnit      时间单位
     * @return 超时时间
     */
    Long getExpire(RedisKeyBuild redisKeyBuild, TimeUnit timeUnit);


    /**
     * 设置缓存
     *
     * @param redisKeyBuild 缓存key
     * @param object        缓存对象
     */
    void set(RedisKeyBuild redisKeyBuild, Object object);

    /**
     * 设置缓存
     *
     * @param redisKeyBuild 缓存key
     * @param object        缓存对象
     * @param ttl           过期时间
     */
    void set(RedisKeyBuild redisKeyBuild, Object object, long ttl);

    /**
     * 设置缓存
     *
     * @param redisKeyBuild 缓存key
     * @param object        缓存对象
     * @param ttl           过期时间
     * @param timeUnit      时间单位
     */
    void set(RedisKeyBuild redisKeyBuild, Object object, long ttl, TimeUnit timeUnit);


    /**
     * 增加(自增长), 负数则为自减
     *
     * @param redisKeyBuild 缓存key
     * @param increment     步长
     * @return
     */
    Long incrBy(RedisKeyBuild redisKeyBuild, long increment);


    /** -------------------hash相关操作------------------------- */

    /**
     * 放置一个键值对
     *
     * @param redisKeyBuild hash键
     * @param hashKey       hash key
     * @param value         hash value
     */
    void putHash(RedisKeyBuild redisKeyBuild, String hashKey, Object value);

    /**
     * 放置一个键值对 并设置过期时间
     *
     * @param redisKeyBuild hash键
     * @param hashKey       hash key
     * @param value         hash value
     * @param ttl           过期时间
     */
    void putHash(RedisKeyBuild redisKeyBuild, String hashKey, Object value, long ttl);

    /**
     * 放置一个键值对 并设置过期时间
     *
     * @param redisKeyBuild hash键
     * @param hashKey       hash key
     * @param value         hash value
     * @param ttl           过期时间
     * @param timeUnit      时间单位
     */
    void putHash(RedisKeyBuild redisKeyBuild, String hashKey, Object value, long ttl, TimeUnit timeUnit);

    /**
     * 放入map中所有键值对
     *
     * @param redisKeyBuild key
     * @param map           hash
     */
    void putHash(RedisKeyBuild redisKeyBuild, Map<String, ?> map);

    /**
     * 放入map中所有键值对 并设置过期时间
     *
     * @param redisKeyBuild key
     * @param map           hash
     * @param ttl           过期时间
     */
    void putHash(RedisKeyBuild redisKeyBuild, Map<String, ?> map, long ttl);

    /**
     * 放入 Map 中所有键值对 并设置过期时间和时间单位
     *
     * @param redisKeyBuild key
     * @param map           hash
     * @param ttl           过期时间
     * @param timeUnit      时间单位
     */
    void putHash(RedisKeyBuild redisKeyBuild, Map<String, ?> map, long ttl, TimeUnit timeUnit);


    /**
     * 从 Hash 中获取普通对象
     *
     * @param redisKeyBuild key
     * @param hashKey       hash key
     * @param clazz         类对象
     * @param <T>           T
     * @return 普通对象
     */
    @SuppressWarnings("all")
    <T> T getForHash(RedisKeyBuild redisKeyBuild, String hashKey, Class<T> clazz);


    /**
     * 从 {@code key} 处获取给定 {@code hashKeys} 的值
     *
     * @param redisKeyBuild key
     * @param hashKeys      hashKeys
     * @param clazz         类对象
     * @param <T>           T
     * @return
     */
    <T> List<T> multiGetForHash(RedisKeyBuild redisKeyBuild, List<String> hashKeys, Class<T> clazz);


    /**
     * 谨慎使用！
     * 获取 Hash Key 下所有值，返回值为map
     *
     * @param redisKeyBuild 缓存key
     * @param clazz         类型
     * @param <T>           泛型
     * @return
     */
    <T> Map<String, T> getAllMapForHash(RedisKeyBuild redisKeyBuild, Class<T> clazz);


    /**
     * 删除hash key
     *
     * @param redisKeyBuild 缓存key
     * @param hashKey       hash中key
     * @return 结果
     */
    Long delForHash(RedisKeyBuild redisKeyBuild, String hashKey);

    /**
     * 批量删除hash key
     *
     * @param redisKeyBuild 缓存key
     * @param hashKeys      hash中key
     * @return 结果
     */
    Long delForHash(RedisKeyBuild redisKeyBuild, Collection<String> hashKeys);


    /* ------------------------list相关操作---------------------------- */


    /**
     * List 从左边放入元素
     *
     * @param redisKeyBuild key
     * @param value         value
     * @return 改动行数
     */
    Long leftPushForList(RedisKeyBuild redisKeyBuild, Object value);

    /**
     * List 从左边放入元素
     *
     * @param redisKeyBuild key
     * @param valueList     valueList
     * @return 改动行数
     */
    Long leftPushAllForList(RedisKeyBuild redisKeyBuild, List<?> valueList);


    /**
     * 如果pivot存在,在pivot左边添加
     *
     * @param redisKeyBuild 缓存key
     * @param pivot         pivot
     * @param value         对象
     * @return 结果
     */
    Long leftPushForList(RedisKeyBuild redisKeyBuild, Object pivot, Object value);

    /**
     * List 从右边放入元素
     *
     * @param redisKeyBuild key
     * @param value         value
     * @return 改动行数
     */
    Long rightPushForList(RedisKeyBuild redisKeyBuild, Object value);


    /**
     * 如果pivot存在,在pivot右边添加
     *
     * @param redisKeyBuild 缓存key
     * @param pivot         pivot
     * @param value         对象
     * @return 结果
     */
    Long rightPushForList(RedisKeyBuild redisKeyBuild, Object pivot, Object value);


    /**
     * 获取列表指定范围内的元素
     *
     * @param redisKeyBuild 缓存key
     * @param start         开始位置, 0是开始位置
     * @param end           结束位置, -1返回所有
     * @param clazz         类型
     * @return 结果
     */
    <T> List<T> rangeForList(RedisKeyBuild redisKeyBuild, long start, long end, Class<T> clazz);


    /**
     * 获取列表长度
     *
     * @param redisKeyBuild
     * @return
     */
    Long lenForList(RedisKeyBuild redisKeyBuild);


    /**
     * 从列表中移除与 value 匹配的元素（LREM）
     *
     * @param redisKeyBuild 缓存key
     * @param value         要移除的元素
     * @param count         count &gt; 0 从头移除最多 count 个；count &lt; 0 从尾移除最多 |count| 个；count = 0 移除全部匹配
     * @return 移除的元素个数
     */
    Long removeForList(RedisKeyBuild redisKeyBuild, Object value, long count);


    /** --------------------set相关操作-------------------------- */


    /**------------------SortedSet相关操作--------------------------------*/


    /**
     * 获取实例
     *
     * @return
     */
    RedisTemplate getInstance();
}
