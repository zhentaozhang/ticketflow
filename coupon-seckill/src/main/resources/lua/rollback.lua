-- ============================================================
-- 库存回补脚本：抢购成功但 Kafka 发送失败时，反向回补 库存+限购计数，并清除幂等标记
-- 对应 docs/01-技术设计.md §8.4
-- KEYS[1] = flash:stock:{activityId}
-- KEYS[2] = flash:limit:{activityId}:{userId}
-- KEYS[3] = flash:dedup:{activityId}:{userId}:{requestId}
-- 返回: 1
-- ============================================================

redis.call('INCR', KEYS[1])

local v = tonumber(redis.call('GET', KEYS[2]) or '0')
if v and v > 0 then
    redis.call('DECR', KEYS[2])
end

redis.call('DEL', KEYS[3])
return 1
