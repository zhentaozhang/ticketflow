package com.ticketflow.redis;


import com.ticketflow.core.RedisKeyManage;
import com.ticketflow.core.SpringUtil;
import lombok.Getter;

import java.util.Objects;

/**
 * Redis key 构建器——自动注入环境隔离前缀。
 * <p>
 * 每次构建 key 时自动在开头加上 getPrefixDistinctionName()-，
 * 确保不同环境（本地 / 测试 / 生产）的 Redis key 不冲突。
 * <p>
 * 例：RedisKeyManage.PROGRAM 的 key 模板为 "program:%s"
 * createRedisKey(PROGRAM, 42) → "local-program:42" 或 "pro-program:42"
 * <p>
 * RedisKeyManage 枚举集中管理所有 key 模板和过期时间，
 * 避免 key 命名散落在业务代码中。
 **/
@Getter
public final class RedisKeyBuild {
    /**
     * 实际使用的key
     *
     */
    private final String relKey;

    private RedisKeyBuild(String relKey) {
        this.relKey = relKey;
    }

    /**
     * 构建真实的key
     *
     * @param redisKeyManage key的枚举
     * @param args           占位符的值
     *
     */
    public static RedisKeyBuild createRedisKey(RedisKeyManage redisKeyManage, Object... args) {
        String redisRelKey = String.format(redisKeyManage.getKey(), args);
        return new RedisKeyBuild(SpringUtil.getPrefixDistinctionName() + "-" + redisRelKey);
    }

    public static String getRedisKey(RedisKeyManage redisKeyManage) {
        return SpringUtil.getPrefixDistinctionName() + "-" + redisKeyManage.getKey();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        RedisKeyBuild that = (RedisKeyBuild) o;
        return relKey.equals(that.relKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(relKey);
    }
}
