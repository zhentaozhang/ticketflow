--- programDataResolution.lua — 节目数据编辑后的原子更新（余票+座位）
--- ARGV[1]: 票档增量列表 [{programTicketRemainNumberHashKey, ticketCategoryId, count}]
--- ARGV[2]: 需删除座位列表 [{seatHashKeyDel, seatIdList}]
--- ARGV[3]: 需新增/更新座位列表 [{seatHashKeyAdd, seatDataList}]
--- 操作顺序：HINCRBY 恢复余票 → HDEL 删除座位 → HMSET 写入新座位
local ticket_category_list = cjson.decode(ARGV[1])
local del_seat_list = cjson.decode(ARGV[2])
local add_seat_data_list = cjson.decode(ARGV[3])

for index,increase_data in ipairs(ticket_category_list) do
    local program_ticket_remain_number_hash_key = increase_data.programTicketRemainNumberHashKey
    local ticket_category_id = increase_data.ticketCategoryId
    local increase_count = increase_data.count
    redis.call('HINCRBY',program_ticket_remain_number_hash_key,ticket_category_id,increase_count)
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
end