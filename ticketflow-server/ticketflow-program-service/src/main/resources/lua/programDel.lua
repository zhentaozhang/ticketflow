--- programDel.lua — 节目全量删除（安全批量清理传入的精准 Redis key）
--- 废除 keys 模糊匹配，严禁在生产执行 O(N) 扫描阻塞 Redis
for i = 1, #KEYS do
    redis.call('del', KEYS[i])
end
return 1