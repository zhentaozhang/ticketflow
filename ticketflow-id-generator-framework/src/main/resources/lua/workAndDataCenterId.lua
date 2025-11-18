--- workAndDataCenterId.lua — Snowflake workerId / dataCenterId 自增分配（原子操作）
--- 以 Redis 持久计数器实现多实例 workerId 分配。
--- 达到 max 后回绕到 0（优先递增 workerId，workerId 回绕时递增 dataCenterId）。
--- 返回：{"workId": N, "dataCenterId": N}
local snowflake_work_id_key = KEYS[1]
local snowflake_data_center_id_key = KEYS[2]
local max_worker_id = tonumber(ARGV[1])
local max_data_center_id = tonumber(ARGV[2])
local return_worker_id = 0
local return_data_center_id = 0
local snowflake_work_id_flag = false
local snowflake_data_center_id_flag = false
local json_result = string.format('{"%s": %d, "%s": %d}',
        'workId', return_worker_id,
        'dataCenterId', return_data_center_id)

if (redis.call('exists', snowflake_work_id_key) == 0) then
    redis.call('set',snowflake_work_id_key,0)
    snowflake_work_id_flag = true
end
if (redis.call('exists', snowflake_data_center_id_key) == 0) then
    redis.call('set',snowflake_data_center_id_key,0)
    snowflake_data_center_id_flag = true
end
if (snowflake_work_id_flag and snowflake_data_center_id_flag) then
    return json_result
end
local snowflake_work_id = tonumber(redis.call('get',snowflake_work_id_key))
local snowflake_data_center_id = tonumber(redis.call('get',snowflake_data_center_id_key))

if (snowflake_work_id == max_worker_id) then
    if (snowflake_data_center_id == max_data_center_id) then
        redis.call('set',snowflake_work_id_key,0)
        redis.call('set',snowflake_data_center_id_key,0)
    else
        return_data_center_id = redis.call('incr',snowflake_data_center_id_key)
    end
else
    return_worker_id = redis.call('incr',snowflake_work_id_key)
end
return string.format('{"%s": %d, "%s": %d}',
        'workId', return_worker_id,
        'dataCenterId', return_data_center_id)