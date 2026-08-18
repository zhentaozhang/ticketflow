-- ============================================================
-- 抢购核心脚本：一个 Lua 原子完成 时间窗校验 + 请求幂等 + 限购计数 + 库存扣减
-- 对应 docs/01-技术设计.md §6.2
-- KEYS[1] = flash:stock:{activityId}           (String 剩余库存)
-- KEYS[2] = flash:limit:{activityId}:{userId}  (String 用户已抢计数)
-- KEYS[3] = flash:meta:{activityId}            (String JSON: {"startTs":..,"endTs":..,"limit":..})
-- KEYS[4] = flash:dedup:{activityId}:{userId}:{requestId} (String 请求幂等标记)
-- ARGV[1] = 当前毫秒时间戳
-- ARGV[2] = 幂等标记 TTL（秒）
-- 返回: 1=成功  -1=售罄  -2=超过限购  -3=未开始/已结束/活动不存在  -4=重复请求
-- ============================================================

local metaJson = redis.call('GET', KEYS[3])
if not metaJson then
    return -3
end
local meta = cjson.decode(metaJson)
local now = tonumber(ARGV[1])
if now < meta.startTs or now > meta.endTs then
    return -3
end

-- 1) 请求幂等：同一 requestId 已成功处理过则拒绝
local dedupOk = redis.call('SET', KEYS[4], '1', 'NX', 'EX', tonumber(ARGV[2]))
if not dedupOk then
    return -4
end

-- 2) 限购计数
local limit = tonumber(meta.limit)
local bought = tonumber(redis.call('GET', KEYS[2]) or '0')
if bought >= limit then
    redis.call('DEL', KEYS[4])
    return -2
end

-- 3) 库存扣减（负库存立即回加，永不出现负库存）
local stock = tonumber(redis.call('DECR', KEYS[1]))
if stock < 0 then
    redis.call('INCR', KEYS[1])
    redis.call('DEL', KEYS[4])
    return -1
end

redis.call('INCR', KEYS[2])
-- 限购计数 TTL 对齐活动结束时间，避免活动结束后残留
local remainSec = math.floor((meta.endTs - now) / 1000)
if remainSec > 0 then
    redis.call('EXPIRE', KEYS[2], remainSec + 3600)
end
return 1
