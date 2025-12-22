--- programDel.lua — 节目全量删除（清理 12 类 Redis key）
--- KEYS[1-12]: 依次为 program / programGroup / programShowTime /
---              seatNoSold / seatLock / seatSold / ticketCategoryList /
---              ticketRemainNumber / record / recordFinish / discardOrder / accountOrderCount
--- 注意：seat* 和 ticketRemainNumber 使用 KEYS 模糊匹配批量删除
local program_key = KEYS[1]
local program_group_key = KEYS[2]
local program_show_time_key = KEYS[3]
local program_seat_no_sold_resolution_hash_key = KEYS[4]
local program_seat_lock_resolution_hash_key = KEYS[5]
local program_seat_sold_resolution_hash_key = KEYS[6]
local program_ticket_category_list_key = KEYS[7]
local program_ticket_remain_number_hash_resolution_key = KEYS[8]
local program_record_hash_key = KEYS[9]
local program_record_finish_hash_key = KEYS[10]
local discard_order_key = KEYS[11]
local account_order_count_key = KEYS[12]

redis.call('del', program_key)
redis.call('del', program_group_key)
redis.call('del', program_show_time_key)
local program_seat_no_sold_resolution_hash_list = redis.call('keys', program_seat_no_sold_resolution_hash_key)
if program_seat_no_sold_resolution_hash_list then
    for index, key in ipairs(program_seat_no_sold_resolution_hash_list) do
        redis.call('del', key)
    end
end
local program_seat_lock_resolution_hash_list = redis.call('keys', program_seat_lock_resolution_hash_key)
if program_seat_lock_resolution_hash_list then
    for index, key in ipairs(program_seat_lock_resolution_hash_list) do
        redis.call('del', key)
    end
end
local program_seat_sold_resolution_hash_list = redis.call('keys', program_seat_sold_resolution_hash_key)
if program_seat_sold_resolution_hash_list then
    for index, key in ipairs(program_seat_sold_resolution_hash_list) do
        redis.call('del', key)
    end
end
redis.call('del', program_ticket_category_list_key)
local program_ticket_remain_number_hash_resolution_list = redis.call('keys', program_ticket_remain_number_hash_resolution_key)
if program_ticket_remain_number_hash_resolution_list then
    for index, key in ipairs(program_ticket_remain_number_hash_resolution_list) do
        redis.call('del', key)
    end
end
redis.call('del', program_record_hash_key)
redis.call('del', program_record_finish_hash_key)
redis.call('del', discard_order_key)
local account_order_count_key_list = redis.call('keys', account_order_count_key)
if account_order_count_key_list then
    for index, key in ipairs(account_order_count_key_list) do
        redis.call('del', key)
    end
end