--- programDataCreateOrderResolution.lua — 创建订单时的座位锁定 + 余票扣减
--- 核心：先完成全部校验，再执行实际扣减，保证座位与余票操作原子执行。
---
--- KEYS[1]: type（恒为 1，用户选座）
--- KEYS[2]: 未售座位 hash key
--- KEYS[3]: 已锁座位 hash key
--- KEYS[4]: 节目 id
--- KEYS[5]: 记录 hash key
--- KEYS[6]: 记录标识
--- KEYS[7]: 记录类型
---
--- ARGV[1]: 票档列表 JSON
--- ARGV[2]: 座位数据 JSON
--- ARGV[3]: 购票人 id 列表 JSON
---
--- 错误码：
--- 40001 — 座位不存在
--- 40002 — 座位已锁定
--- 40003 — 座位已售出
--- 40008 — 价格不一致
--- 40010 — 票档不存在
--- 40011 — 余票不足
--- 0     — 成功
---
--- 执行流程：
--- 1. 校验票档与余票
--- 2. 校验座位与价格
--- 3. 构建流水记录
--- 4. 扣减余票
--- 5. 将座位从未售迁移到锁定
--- 6. 写入流水
--- 7. 返回锁定后的座位

local type = tonumber(KEYS[1])
local placeholder_seat_no_sold_hash_key = KEYS[2]
local placeholder_seat_lock_hash_key = KEYS[3]
local program_id = KEYS[4]
local record_hash_key = KEYS[5]
local identifier_id = KEYS[6]
local record_type = KEYS[7]

-- 解析请求参数。
local ticket_count_list = cjson.decode(ARGV[1])
local ticket_user_id_list = cjson.decode(ARGV[3])

-- 保存最终可以购买的座位，同时构建票档和座位的变更记录。
local purchase_seat_list = {}
local ticket_category_record_list = {}

-- 用于校验请求价格不能高于 Redis 中的真实座位价格。
local total_seat_dto_price = 0
local total_seat_vo_price = 0

-- 锁定状态。
local lock_status = 2

-- 第一阶段：校验票档和余票。
-- 这里只做校验，不立即扣库存，避免前面座位校验失败后产生数据变化。
if (type == 1) then
	for index, ticket_count in ipairs(ticket_count_list) do

		local ticket_remain_number_hash_key =
			ticket_count.programTicketRemainNumberHashKey

		local ticket_category_id = ticket_count.ticketCategoryId
		local count = ticket_count.ticketCount

		-- 从 Redis 获取当前票档余票。
		local remain_number_str =
			redis.call('hget',
				ticket_remain_number_hash_key,
				tostring(ticket_category_id))

		-- 票档不存在，直接失败。
		if not remain_number_str then
			return string.format('{"%s": %d}', 'code', 40010)
		end

		local remain_number = tonumber(remain_number_str)

		-- 余票不足，直接失败。
		if (count > remain_number) then
			return string.format('{"%s": %d}', 'code', 40011)
		end

		-- 先记录本次票档库存变化，后面统一写入流水。
		local ticket_category_record = {}
		ticket_category_record.ticketCategoryId = ticket_category_id
		ticket_category_record.beforeAmount = remain_number
		ticket_category_record.afterAmount = remain_number - count
		ticket_category_record.changeAmount = count

		table.insert(ticket_category_record_list, ticket_category_record)
	end

	-- 第二阶段：校验座位。
	local seat_data_list = cjson.decode(ARGV[2])
	local seat_index = 0

	for index, seatData in pairs(seat_data_list) do

		local seat_no_sold_hash_key = seatData.seatNoSoldHashKey
		-- 锁定/已售集合的 key（已拼好），失败时用来分辨“已被抢”还是“不存在”
		local seat_lock_hash_key = seatData.seatLockHashKey
		local seat_sold_hash_key = seatData.seatSoldHashKey
		local seat_dto_list = cjson.decode(seatData.seatDataList)

		for index2, seat_dto in ipairs(seat_dto_list) do
			seat_index = seat_index + 1

			local id = seat_dto.id
			local seat_dto_price = seat_dto.price

			-- 从 Redis 获取当前座位。
			local seat_vo_str =
				redis.call('hget',
					seat_no_sold_hash_key,
					tostring(id))

			-- 座位不在“未售”集合里。
			-- 注意：座位一旦被锁就会从未售集合里删掉，所以并发抢同一个座位时，
			-- 后到的请求拿到的是 nil——如果直接回 40001（座位不存在），
			-- 就把“被别人抢先了”误报成“这个座位不存在”，用户看到的原因不对、
			-- 失败归因也会跟着错。所以这里再查一眼锁定/已售集合，把原因说准。
			if not seat_vo_str then
				if redis.call('hexists', seat_lock_hash_key, tostring(id)) == 1 then
					return string.format('{"%s": %d}', 'code', 40002)
				end
				if redis.call('hexists', seat_sold_hash_key, tostring(id)) == 1 then
					return string.format('{"%s": %d}', 'code', 40003)
				end
				return string.format('{"%s": %d}', 'code', 40001)
			end

			local seat_vo = cjson.decode(seat_vo_str)

			-- 座位已经被其他请求锁定或售出，直接失败。
			if (seat_vo.sellStatus == 2) then
				return string.format('{"%s": %d}', 'code', 40002)
			end

			if (seat_vo.sellStatus == 3) then
				return string.format('{"%s": %d}', 'code', 40003)
			end

			-- 校验通过，加入最终购买座位列表。
			table.insert(purchase_seat_list, seat_vo)

			-- 校验请求价格与缓存价格，防止客户端篡改价格。
			total_seat_dto_price =
				total_seat_dto_price + seat_dto_price

			total_seat_vo_price =
				total_seat_vo_price + seat_vo.price

			if (total_seat_dto_price > total_seat_vo_price) then
				return string.format('{"%s": %d}', 'code', 40008)
			end

			-- 构建座位状态变更流水，并绑定购票人。
			for index3, ticket_category_record in pairs(ticket_category_record_list) do
				if ticket_category_record.ticketCategoryId == seat_vo.ticketCategoryId then

					if not ticket_category_record.seatRecordList then
						ticket_category_record.seatRecordList = {}
					end

					local seat_record = {}
					seat_record.ticketCategoryId = seat_vo.ticketCategoryId
					seat_record.seatId = id
					seat_record.beforeStatus = seat_vo.sellStatus
					seat_record.afterStatus = lock_status
					seat_record.ticketUserId = ticket_user_id_list[seat_index]

					seat_vo.ticketUserId = ticket_user_id_list[seat_index]

					table.insert(
						ticket_category_record.seatRecordList,
						seat_record)
				end
			end
		end
	end
end

-- 第三阶段：所有校验通过，开始执行真正的数据变更。
-- 从这里开始不再返回业务校验错误，保证扣库存、迁移座位、写流水处于同一个 Lua 原子执行过程中。

-- 按票档整理座位 ID，用于从“未售座位”Hash 中批量删除。
local seat_id_list = {}

-- 按票档整理完整座位数据，用于批量写入“锁定座位”Hash。
local seat_data_list = {}

for index, seat in ipairs(purchase_seat_list) do
	local seat_id = seat.id
	local ticket_category_id = seat.ticketCategoryId

	if not seat_id_list[ticket_category_id] then
		seat_id_list[ticket_category_id] = {}
	end

	table.insert(
		seat_id_list[ticket_category_id],
		tostring(seat_id))

	if not seat_data_list[ticket_category_id] then
		seat_data_list[ticket_category_id] = {}
	end

	-- 座位状态修改为“锁定”，准备写入锁定座位 Hash。
	seat.sellStatus = lock_status

	-- Hash 的 field 先放座位 ID，再放对应的座位 JSON。
	table.insert(
		seat_data_list[ticket_category_id],
		tostring(seat_id))

	table.insert(
		seat_data_list[ticket_category_id],
		cjson.encode(seat))
end

-- 第四阶段：扣减各票档余票。
for index, ticket_count in ipairs(ticket_count_list) do

	local ticket_remain_number_hash_key =
		ticket_count.programTicketRemainNumberHashKey

	local ticket_category_id = ticket_count.ticketCategoryId
	local count = ticket_count.ticketCount

	redis.call(
		'hincrby',
		ticket_remain_number_hash_key,
		ticket_category_id,
		"-" .. count)
end

-- 第五阶段：将座位从“未售”迁移到“锁定”。
-- 先删除未售状态，再写入锁定状态，避免同一座位同时存在于两个集合。
for ticket_category_id, seat_id_array in pairs(seat_id_list) do
	redis.call(
		'hdel',
		string.format(
			placeholder_seat_no_sold_hash_key,
			program_id,
			tostring(ticket_category_id)),
		unpack(seat_id_array))
end

for ticket_category_id, seat_data_array in pairs(seat_data_list) do
	redis.call(
		'hmset',
		string.format(
			placeholder_seat_lock_hash_key,
			program_id,
			tostring(ticket_category_id)),
		unpack(seat_data_array))
end

-- 第六阶段：使用 Redis 服务端时间写入流水。
-- 使用 Redis TIME，避免应用服务器之间存在时钟偏差。
local time = redis.call("time")
local currentTimeMillis =
	(time[1] * 1000) + math.floor(time[2] / 1000)

local purchase_record = {
	recordType = record_type,
	timestamp = currentTimeMillis,
	ticketCategoryRecordList = ticket_category_record_list
}

redis.call(
	'hset',
	string.format(record_hash_key, program_id),
	identifier_id,
	cjson.encode(purchase_record))

-- 第七阶段：返回执行结果和锁定后的座位信息。
return string.format(
	'{"%s": %d, "%s": %s}',
	'code',
	0,
	'purchaseSeatList',
	cjson.encode(purchase_seat_list))