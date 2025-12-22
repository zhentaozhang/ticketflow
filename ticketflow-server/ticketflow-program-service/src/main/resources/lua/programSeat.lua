--- programSeat.lua — 合并查询未售/锁定/已售三区所有座位
--- KEYS[1]: 未售座位 hash key
--- KEYS[2]: 锁定座位 hash key
--- KEYS[3]: 已售座位 hash key
--- 返回：所有座位 JSON 字符串数组（不区分状态）
local seat_no_sold_resolution_hash_key = KEYS[1]
local seat_lock_resolution_hash_key = KEYS[2]
local seat_sold_resolution_hash_key = KEYS[3]

local seat_list = {}

local seat_no_sold_resolution_list = redis.call('hvals', seat_no_sold_resolution_hash_key)
if seat_no_sold_resolution_list then
    for index, seat in ipairs(seat_no_sold_resolution_list) do
        table.insert(seat_list,seat)
    end
end
local seat_lock_resolution_list = redis.call('hvals', seat_lock_resolution_hash_key)
if seat_lock_resolution_list then
    for index, seat in ipairs(seat_lock_resolution_list) do
        table.insert(seat_list,seat)
    end
end
local seat_sold_resolution_list = redis.call('hvals', seat_sold_resolution_hash_key)
if seat_sold_resolution_list then
    for index, seat in ipairs(seat_sold_resolution_list) do
        table.insert(seat_list,seat)
    end
end
return seat_list