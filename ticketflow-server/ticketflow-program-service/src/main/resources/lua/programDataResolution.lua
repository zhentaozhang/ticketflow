--- programDataResolution.lua — 节目数据编辑后的原子更新（余票+座位）
--- ARGV[1]: 票档增量列表 [{programTicketRemainNumberHashKey, ticketCategoryId, count}]
--- ARGV[2]: 需删除座位列表 [{seatHashKeyDel, seatIdList}]
--- ARGV[3]: 需新增/更新座位列表 [{seatHashKeyAdd, seatDataList}]
--- ARGV[4]: 座位/余票 hash 的有效期（秒），仅在新建 key 时补设
--- 操作顺序：HINCRBY 恢复余票 → HDEL 删除座位 → HMSET 写入新座位
local ticket_category_list = cjson.decode(ARGV[1])
local del_seat_list = cjson.decode(ARGV[2])
local add_seat_data_list = cjson.decode(ARGV[3])
-- 派生 hash 的有效期（秒），缺省 30 分钟。
-- 这里唯一的职责是「新建 key 时补设期」，已有 TTL 一律不延长。
local cache_expire_seconds = ARGV[4] and tonumber(ARGV[4]) or 0
if (not cache_expire_seconds or cache_expire_seconds <= 0) then
	cache_expire_seconds = 1800
end

-- HDEL 清空一个座位 hash 会让整个 key 消失，之后 HMSET/HINCRBY 重建的 key 没有 TTL。
local function apply_expire_if_absent(hash_key, expire_seconds)
	if (redis.call('ttl', hash_key) < 0) then
		redis.call('expire', hash_key, expire_seconds)
	end
end

for index,increase_data in ipairs(ticket_category_list) do
    local program_ticket_remain_number_hash_key = increase_data.programTicketRemainNumberHashKey
    local ticket_category_id = increase_data.ticketCategoryId
    local increase_count = increase_data.count
    redis.call('HINCRBY',program_ticket_remain_number_hash_key,ticket_category_id,increase_count)
    apply_expire_if_absent(program_ticket_remain_number_hash_key, cache_expire_seconds)
end
for index, seat in pairs(del_seat_list) do
    local seat_hash_key_del = seat.seatHashKeyDel
    local seat_id_list = seat.seatIdList
    redis.call('HDEL',seat_hash_key_del,unpack(seat_id_list))
end
for index, seat in pairs(add_seat_data_list) do
    local seat_hash_key_add = seat.seatHashKeyAdd
    local seat_data_list = seat.seatDataList
    redis.call('HMSET',seat_hash_key_add,unpack(seat_data_list))
    apply_expire_if_absent(seat_hash_key_add, cache_expire_seconds)
end