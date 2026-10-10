--- loginErrorCount.lua — 登录失败次数原子累加（INCR + 首次设置 EXPIRE）
--- KEYS[1]: 失败计数 key
--- ARGV[1]: 过期时间（秒）
--- 返回：累加后的计数
---
--- 将 INCR 与 EXPIRE 合并为一次原子操作，避免两条独立命令之间进程崩溃导致 TTL 丢失、计数永不过期
local count = redis.call('incr', KEYS[1])
if count == 1 then
    redis.call('expire', KEYS[1], tonumber(ARGV[1]))
end
return count
