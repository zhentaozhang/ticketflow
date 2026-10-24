--- OrderProgramDataResolution.lua — 订单操作后更新座位状态（原子操作）
--- KEYS[1]: operate_order_status (2=订单取消/退款, 3=订单支付)
--- KEYS[2]: 节目 id
--- KEYS[3]: 记录 hash key
--- KEYS[4]: 记录标识
--- KEYS[5]: 记录类型
--- ARGV[1]: 解除锁定座位列表 JSON [{programSeatLockHashKey, unLockSeatIdList}]
--- ARGV[2]: 新增座位数据 JSON [{seatHashKeyAdd, seatDataList}]
--- ARGV[3]: 票档数据 JSON [{programTicketRemainNumberHashKey, ticketCategoryId, count}]
--- ARGV[4]: 座位-购票人映射 JSON [{seatId, ticketUserId}]
--- ARGV[5]: 座位/余票 hash 的有效期（秒），仅在新建 key 时补设
--- ARGV[6]: 流水 hash 的有效期（秒），仅在新建 key 时补设
---
--- 操作逻辑：
---   1. 从锁定集合 (lock hash) 中 del 已处理座位
---   2. 根据操作类型写入目标集合：
---       取消 → HMSET 到未售集合 (no_sold)，恢复余票 (HINCRBY +count)
---       支付 → HMSET 到已售集合 (sold)，余票不变化
---   3. 写入流水记录 (purchase_record)
-- 订单操作的类型 2：订单取消 3：订单支付
local operate_order_status = tonumber(KEYS[1])
-- 节目id
local program_id = KEYS[2]
-- 记录的key
local record_hash_key = KEYS[3]
-- 记录标识
local identifier_id = KEYS[4]
-- 记录类型
local record_type = KEYS[5]
-- 解除锁定的座位id列表
local un_lock_seat_id_json_array = cjson.decode(ARGV[1])
-- 座位数据
local add_seat_data_json_array = cjson.decode(ARGV[2])
-- 票档数量数据
local ticket_category_list = cjson.decode(ARGV[3])
-- 座位id和购票人id的映射
local seat_id_and_ticket_user_id_domain_list = cjson.decode(ARGV[4])
-- 派生 hash 的有效期（秒）。缺省值取安全下限：座位/余票 30 分钟，流水 7 天。
-- 这里唯一的职责是「新建 key 时补设期」，已有 TTL 一律不延长。
local cache_expire_seconds = ARGV[5] and tonumber(ARGV[5]) or 0
local record_expire_seconds = ARGV[6] and tonumber(ARGV[6]) or 0
if (not cache_expire_seconds or cache_expire_seconds <= 0) then
	cache_expire_seconds = 1800
end
if (not record_expire_seconds or record_expire_seconds <= 0) then
	record_expire_seconds = 604800
end

-- HDEL 会把 hash 清空成「整个 key 消失」，随后 HMSET/HINCRBY 重建的 key 没有任何 TTL，
-- 派生数据就会永久驻留。每个写点补一次设期，让重建出来的 key 也能自愈过期。
local function apply_expire_if_absent(hash_key, expire_seconds)
	if (redis.call('ttl', hash_key) < 0) then
		redis.call('expire', hash_key, expire_seconds)
	end
end
-- 锁定状态
local lock_status = 2
-- 将锁定的座位集合进行扣除
for index, un_lock_seat_id_json_object in pairs(un_lock_seat_id_json_array) do
	local program_seat_hash_key = un_lock_seat_id_json_object.programSeatLockHashKey
	local un_lock_seat_id_list = un_lock_seat_id_json_object.unLockSeatIdList
	redis.call('HDEL',program_seat_hash_key,unpack(un_lock_seat_id_list))
end
-- 票档集合记录
local ticket_category_record_list = {}
-- 座位集合记录
local total_seat_record_list = {}
-- 如果是订单取消的操作，那么添加到未售卖的座位hash数据
-- 如果是订单支付的操作，那么添加到已售卖的座位hash数据
for index, add_seat_data_json_object in pairs(add_seat_data_json_array) do
	local seat_hash_key_add = add_seat_data_json_object.seatHashKeyAdd
	local seat_data_list = add_seat_data_json_object.seatDataList
	redis.call('HMSET',seat_hash_key_add,unpack(seat_data_list))
	apply_expire_if_absent(seat_hash_key_add, cache_expire_seconds)
	-- 构建座位记录数据
	for i = 2, #seat_data_list, 2 do
		local seat_object_str = seat_data_list[i]
		local seat_object = cjson.decode(seat_object_str)
		-- 座位记录
		local seat_record = {}
		seat_record.ticketCategoryId = seat_object.ticketCategoryId
		seat_record.seatId = seat_object.id
		seat_record.beforeStatus = lock_status
		seat_record.afterStatus = seat_object.sellStatus
		-- 把购票人id映射上
		for j, seat_id_and_ticket_user_id_domain in ipairs(seat_id_and_ticket_user_id_domain_list) do
			if (seat_record.seatId == seat_id_and_ticket_user_id_domain.seatId) then
				seat_record.ticketUserId = seat_id_and_ticket_user_id_domain.ticketUserId
				break
			end
		end
		-- 将座位记录添加到座位集合记录中
		table.insert(total_seat_record_list,seat_record)
	end
	-- 循环票档集合
	for index,ticket_category in ipairs(ticket_category_list) do
		local program_ticket_remain_number_hash_key = ticket_category.programTicketRemainNumberHashKey
		local ticket_category_id_str = ticket_category.ticketCategoryId
		-- 从缓存中获取相应票档数量
		local remain_number_str = redis.call('hget', program_ticket_remain_number_hash_key, tostring(ticket_category_id_str))
		local remain_number = tonumber(remain_number_str)
		-- 票档记录
		local ticket_category_record = {}
		ticket_category_record.ticketCategoryId = tonumber(ticket_category_id_str)
		-- 对于支付成功的操作，票档数量是没有变化的，所以之前和之后数量相同，改变数量是0
		ticket_category_record.beforeAmount = remain_number
		ticket_category_record.afterAmount = remain_number
		ticket_category_record.changeAmount = 0
		-- 将票档对应的座位匹配上
		local seat_record_list = {}
		for index2,seat_record in ipairs(total_seat_record_list) do
			if (ticket_category_record.ticketCategoryId == seat_record.ticketCategoryId) then
				table.insert(seat_record_list,seat_record)
			end
		end
		ticket_category_record.seatRecordList = seat_record_list
		table.insert(ticket_category_record_list,ticket_category_record)
	end
end
-- 如果是将订单取消
if (operate_order_status == 2) then
-- 恢复库存
	for index,increase_data in ipairs(ticket_category_list) do
	-- 票档数量的key
		local program_ticket_remain_number_hash_key = increase_data.programTicketRemainNumberHashKey
		local ticket_category_id_str = increase_data.ticketCategoryId
		local increase_count = increase_data.count
		redis.call('HINCRBY',program_ticket_remain_number_hash_key,ticket_category_id_str,increase_count)
		apply_expire_if_absent(program_ticket_remain_number_hash_key, cache_expire_seconds)
		for index2,ticket_category_record in ipairs(ticket_category_record_list) do
			if tonumber(ticket_category_id_str) == ticket_category_record.ticketCategoryId then
				ticket_category_record.afterAmount = ticket_category_record.beforeAmount + increase_count
				ticket_category_record.changeAmount = increase_count
			end
		end
	end
end
-- 获取Redis服务器的当前时间（秒和微秒）
local time = redis.call("time")
-- 转换为毫秒级时间戳
local currentTimeMillis = (time[1] * 1000) + math.floor(time[2] / 1000)
-- 记录流水的完整体
local purchase_record = {
	recordType = record_type,
	timestamp = currentTimeMillis,
	ticketCategoryRecordList = ticket_category_record_list
}
local program_record_hash_key = string.format(record_hash_key,program_id)
redis.call('hset',program_record_hash_key,identifier_id,cjson.encode(purchase_record))
apply_expire_if_absent(program_record_hash_key, record_expire_seconds)
