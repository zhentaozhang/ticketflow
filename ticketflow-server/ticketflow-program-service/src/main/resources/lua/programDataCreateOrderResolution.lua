--- programDataCreateOrderResolution.lua — 创建订单时的座位锁定 + 余票扣减（原子操作）
--- KEYS[1]: type (1=用户选座, 2=自动匹配)
--- KEYS[2]: 未售座位 hash key
--- KEYS[3]: 已锁座位 hash key
--- KEYS[4]: 节目 id
--- KEYS[5]: 记录 hash key (d_mai_program_record_%s)
--- KEYS[6]: 记录标识 (reduce_xxx)
--- KEYS[7]: 记录类型 (reduce)
--- ARGV[1]: 票档列表 JSON [{ticketCategoryId, ticketCount, programTicketRemainNumberHashKey}]
--- ARGV[2]: 座位数据 JSON（type=1 时用）
--- ARGV[3]: 购票人 id 列表 JSON（type=1 时用）
---
--- 错误码：
---   40001 — 座位不存在（hget 为空）
---   40002 — 座位已锁定（sellStatus=2）
---   40003 — 座位已售出（sellStatus=3）
---   40004 — 自动匹配座位不足（邻座数量 < 需求）
---   40008 — 价格不一致（入参价格 > 缓存价格）
---   40010 — 票档不存在（hget remain_number 为空）
---   40011 — 余票不足（入参数量 > 缓存余票）
---   0     — 成功
---
--- 操作顺序：验证→锁定座位 (hdel no_sold, hmset lock)→扣余票 (HINCRBY -count)→写流水记录
-- 类型 1 用户选座位 2自动匹配座位
local type = tonumber(KEYS[1])
-- 没有售卖的座位key
local placeholder_seat_no_sold_hash_key = KEYS[2]
-- 锁定的座位key
local placeholder_seat_lock_hash_key = KEYS[3]
-- 节目id
local program_id = KEYS[4]
-- 记录的key 对应的真正数据是 d_mai_program_record_%s
local record_hash_key = KEYS[5]
-- 记录标识  对应的真正数据是 reduce_993369070199742464_992950774744588290
local identifier_id = KEYS[6]
-- 记录类型 对应的真正数据是 reduce
local record_type = KEYS[7]
-- 要购买的票档 包括票档id和票档数量
local ticket_count_list = cjson.decode(ARGV[1])
-- 购票人id集合 对应的数据是购票人id集合
local ticket_user_id_list = cjson.decode(ARGV[3])
-- 过滤后符合条件可以购买的座位集合
local purchase_seat_list = {}
-- 入参座位价格总和
local total_seat_dto_price = 0
-- 缓存座位价格总和
local total_seat_vo_price = 0
-- 记录的票档集合记录 拼接后的记录的票档集合
local ticket_category_record_list = {}
-- 锁定状态
local lock_status = 2
-- 匹配座位算法 
local function find_adjacent_seats(all_seats, seat_count)
    local adjacent_seats = {}

    -- 对可用座位排序
    table.sort(all_seats, function(s1, s2)
        if s1.rowCode == s2.rowCode then
            return s1.colCode < s2.colCode
        else
            return s1.rowCode < s2.rowCode
        end
    end)

    -- 寻找相邻座位
    for i = 1, #all_seats - seat_count + 1 do
        local seats_found = true
        for j = 0, seat_count - 2 do
            local current = all_seats[i + j]
            local next = all_seats[i + j + 1]

            if not (current.rowCode == next.rowCode and next.colCode - current.colCode == 1) then
                seats_found = false
                break
            end
        end
        if seats_found then
            for k = 0, seat_count - 1 do
                table.insert(adjacent_seats, all_seats[i + k])
            end
            return adjacent_seats
        end
    end
    -- 如果没有找到，返回空列表
    return adjacent_seats
end

-- 入参座位存在
if (type == 1) then
    for index,ticket_count in ipairs(ticket_count_list) do
        -- 票档数量的key
        local ticket_remain_number_hash_key = ticket_count.programTicketRemainNumberHashKey
        -- 入参座位的票档id
        local ticket_category_id = ticket_count.ticketCategoryId
        -- 入参座位的票档数量
        local count = ticket_count.ticketCount
        -- 从缓存中获取相应票档数量
        local remain_number_str = redis.call('hget', ticket_remain_number_hash_key, tostring(ticket_category_id))
        -- 如果为空直接返回
        if not remain_number_str then
            return string.format('{"%s": %d}', 'code', 40010)
        end
        local remain_number = tonumber(remain_number_str)
        -- 入参座位的票档数量大于缓存中获取相应票档数量，说明票档数量不足，直接返回
        if (count > remain_number) then
            return string.format('{"%s": %d}', 'code', 40011)
        end
        -- 票档记录
        local ticket_category_record = {}
        ticket_category_record.ticketCategoryId = ticket_category_id
        ticket_category_record.beforeAmount = remain_number
        ticket_category_record.afterAmount = remain_number - count
        ticket_category_record.changeAmount = count

        table.insert(ticket_category_record_list,ticket_category_record)
    end
    
    -- 座位集合
    local seat_data_list= cjson.decode(ARGV[2])
    local seat_index = 0
    for index, seatData in pairs(seat_data_list) do
        -- 没有售卖的座位key
        local seat_no_sold_hash_key = seatData.seatNoSoldHashKey;
        -- 入参座位集合
        local seat_dto_list = cjson.decode(seatData.seatDataList)
        for index2,seat_dto in ipairs(seat_dto_list) do
            seat_index = seat_index + 1
            -- 入参座位id
            local id = seat_dto.id
            -- 入参座位价格
            local seat_dto_price = seat_dto.price
            -- 根据座位id从缓存中没有售卖的座位
            local seat_vo_str = redis.call('hget', seat_no_sold_hash_key, tostring(id))
            -- 如果从缓存中为空，则直接返回
            if not seat_vo_str then
                return string.format('{"%s": %d}', 'code', 40001)
            end
            local seat_vo = cjson.decode(seat_vo_str)
            -- 如果从缓存查询的座位状态是锁定的，直接返回
            if (seat_vo.sellStatus == 2) then
                return string.format('{"%s": %d}', 'code', 40002)
            end
            -- 如果从缓存查询的座位状态是已经售卖的，直接返回
            if (seat_vo.sellStatus == 3) then
                return string.format('{"%s": %d}', 'code', 40003)
            end
            table.insert(purchase_seat_list,seat_vo)
            -- 入参座位价格累加
            total_seat_dto_price = total_seat_dto_price + seat_dto_price
            -- 缓存座位价格累加
            total_seat_vo_price = total_seat_vo_price + seat_vo.price
            if (total_seat_dto_price > total_seat_vo_price) then
                return string.format('{"%s": %d}', 'code', 40008)
            end
            
            for index3, ticket_category_record in pairs(ticket_category_record_list) do
                if ticket_category_record.ticketCategoryId == seat_vo.ticketCategoryId then
                    -- 先构建好座位记录
                    if not ticket_category_record.seatRecordList then
                        ticket_category_record.seatRecordList = {}
                    end
                    -- 座位记录
                    local seat_record = {}
                    seat_record.ticketCategoryId = seat_vo.ticketCategoryId
                    seat_record.seatId = id
                    seat_record.beforeStatus = seat_vo.sellStatus
                    seat_record.afterStatus = lock_status
                    -- 绑定上购票人id
                    seat_record.ticketUserId = ticket_user_id_list[seat_index]
                    seat_vo.ticketUserId = ticket_user_id_list[seat_index]
                    table.insert(ticket_category_record.seatRecordList,seat_record)
                end
            end
        end
    end
end
-- 入参座位不存在
if (type == 2) then
    -- 这里的外层循环其实就一次
    for index,ticket_count in ipairs(ticket_count_list) do
        -- 票档数量的key
        local ticket_remain_number_hash_key = ticket_count.programTicketRemainNumberHashKey
        -- 入参选择的票档id
        local ticket_category_id = ticket_count.ticketCategoryId
        -- 入参选择的票档数量
        local count = ticket_count.ticketCount
        -- 从缓存中获取相应票档数量
        local remain_number_str = redis.call('hget', ticket_remain_number_hash_key, tostring(ticket_category_id))
        -- 如果为空直接返回
        if not remain_number_str then
            return string.format('{"%s": %d}', 'code', 40010)
        end
        local remain_number = tonumber(remain_number_str)
        -- 入参的票档数量大于缓存中获取相应票档数量，说明票档数量不足，直接返回
        if (count > remain_number) then
            return string.format('{"%s": %d}', 'code', 40011)
        end

        -- 票档记录
        local ticket_category_record = {}
        ticket_category_record.ticketCategoryId = ticket_category_id
        ticket_category_record.beforeAmount = remain_number
        ticket_category_record.afterAmount = remain_number - count
        ticket_category_record.changeAmount = count

        table.insert(ticket_category_record_list,ticket_category_record)
        
        local seat_no_sold_hash_key = ticket_count.seatNoSoldHashKey
        -- 获取没有售卖的座位集合
        local seat_vo_no_sold_str_list = redis.call('hvals',seat_no_sold_hash_key)
        local filter_seat_vo_no_sold_list = {}
        -- 这里遍历的原因，座位集合是以hash存储在缓存中，而每个座位是字符串，要把字符串转成对象
        for index,seat_vo_no_sold_str in ipairs(seat_vo_no_sold_str_list) do
            local seat_vo_no_sold = cjson.decode(seat_vo_no_sold_str)
            table.insert(filter_seat_vo_no_sold_list,seat_vo_no_sold)
        end
        -- 利用算法自动根据人数和票档进行分配相邻座位
        purchase_seat_list = find_adjacent_seats(filter_seat_vo_no_sold_list,count)
        -- 如果匹配出的数量 < 对应的购买数量，直接返回
        if (#purchase_seat_list < count) then
            return string.format('{"%s": %d}', 'code', 40004)
        end

        for index2,purchase_seat in ipairs(purchase_seat_list) do
            -- 先构建好座位记录
            if not ticket_category_record.seatRecordList then
                ticket_category_record.seatRecordList = {}
            end
            -- 座位记录
            local seat_record = {}
            seat_record.ticketCategoryId = purchase_seat.ticketCategoryId
            seat_record.seatId = purchase_seat.id
            seat_record.beforeStatus = purchase_seat.sellStatus
            seat_record.afterStatus = lock_status
            seat_record.ticketUserId = ticket_user_id_list[index2]
            -- 绑定上购票人id
            purchase_seat.ticketUserId = ticket_user_id_list[index2]
            table.insert(ticket_category_record.seatRecordList,seat_record)
        end
        
    end
end
-- 经过以上的验证，说明座位和票档数量是够用的，下面开始真正的锁定座位和扣除票档数量操作
-- 要注意 seat_id_list数组的索引值是ticket_category_id(票档id)，数组的值是seat_id_array(座位id数组)
local seat_id_list = {}
-- 要注意 seat_data_list数组的索引值是ticket_category_id(票档id)，数组的值是seat_data_array(座位数据数组)
local seat_data_list = {}
for index,seat in ipairs(purchase_seat_list) do
    local seat_id = seat.id
    local ticket_category_id = seat.ticketCategoryId
    if not seat_id_list[ticket_category_id] then
        seat_id_list[ticket_category_id] = {}
    end
    table.insert(seat_id_list[ticket_category_id], tostring(seat_id))

    if not seat_data_list[ticket_category_id] then
        seat_data_list[ticket_category_id] = {}
    end
    -- 这里在放入值的时候先是放入了座位id
    table.insert(seat_data_list[ticket_category_id], tostring(seat_id))
    seat.sellStatus = lock_status
    -- 然后又放入了座位数据
    table.insert(seat_data_list[ticket_category_id], cjson.encode(seat))
end
-- 扣票档数量
for index,ticket_count in ipairs(ticket_count_list) do
    -- 票档数量的key
    local ticket_remain_number_hash_key = ticket_count.programTicketRemainNumberHashKey
    -- 票档id
    local ticket_category_id = ticket_count.ticketCategoryId
    -- 票档数量
    local count = ticket_count.ticketCount
    redis.call('hincrby',ticket_remain_number_hash_key,ticket_category_id,"-" .. count)
end
-- 将没有售卖的座位删除
for ticket_category_id, seat_id_array in pairs(seat_id_list) do
    redis.call('hdel',string.format(placeholder_seat_no_sold_hash_key,program_id,tostring(ticket_category_id)),unpack(seat_id_array))
end
-- 再将座位数据添加到锁定的座位中
for ticket_category_id, seat_data_array in pairs(seat_data_list) do
    redis.call('hmset',string.format(placeholder_seat_lock_hash_key,program_id,tostring(ticket_category_id)),unpack(seat_data_array))
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
redis.call('hset',string.format(record_hash_key,program_id),identifier_id,cjson.encode(purchase_record))

return string.format('{"%s": %d, "%s": %s}', 'code', 0, 'purchaseSeatList', cjson.encode(purchase_seat_list))