--- programDataCreateOrderResolutionV5.lua — V5 创建订单：幂等 + 座位锁定 + 余票扣减（原子操作）
--- V4 脚本基础上增加"幂等守卫"：SETNX 同一 userId+programId 的提交标记，原子完成"幂等检查 + 校验 + 扣减"。
--- 相比 V4（@RepeatExecuteLimit 独立 2-3 次 Redis 往返 + 本地锁），V5 将幂等检查并入 Lua 单脚本，请求侧仅 1 次 EVAL。
--- KEYS[1]: type (恒为 1, 用户选座)
--- KEYS[2]: 未售座位 hash key
--- KEYS[3]: 已锁座位 hash key
--- KEYS[4]: 节目 id
--- KEYS[5]: 记录 hash key (d_mai_program_record_%s)
--- KEYS[6]: 记录标识 (reduce_xxx) 也作为幂等标记的 value
--- KEYS[7]: 记录类型 (reduce)
--- KEYS[8]: 幂等 key（同一 userId+programId 的提交标记，EX ttl 秒）
--- KEYS[9]: 账户订单计数 key (d_mai_account_order_count_%s_%s，String 类型，限购校验+扣减后累加)
--- ARGV[1]: 票档列表 JSON [{ticketCategoryId, ticketCount, programTicketRemainNumberHashKey}]
--- ARGV[2]: 座位数据 JSON
--- ARGV[3]: 购票人 id 列表 JSON
--- ARGV[4]: 幂等标记 TTL（秒）
--- ARGV[5]: 该用户限购数量 perAccountLimitPurchaseCount（0/null=不限购）
--- ARGV[6]: 本次购票总数（限购校验与计数累加使用）
---
--- 错误码：
---   40035  — 重复提交（同一用户同一节目在幂等窗口内再次提交）
---   50009  — 超出该用户限购数量
---   40001  — 座位不存在（hget 为空）
---   40002  — 座位已锁定（sellStatus=2）
---   40003  — 座位已售出（sellStatus=3）
---   40008  — 价格不一致（入参价格 > 缓存价格）
---   40010  — 票档不存在（hget remain_number 为空）
---   40011  — 余票不足（入参数量 > 缓存余票）
---   0     — 成功
---
--- 操作顺序：幂等守卫→验证→锁定座位 (hdel no_sold, hmset lock)→扣余票 (HINCRBY -count)→写流水记录

local function fail(code)
    redis.call('DEL', KEYS[8])
    return string.format('{"%s": %d}', 'code', code)
end

-- 幂等守卫：SETNX 失败说明同一 userId+programId 正在提交或已提交过（TTL 内），直接拒绝
local idem_result = redis.call('SET', KEYS[8], KEYS[6], 'NX', 'EX', tonumber(ARGV[4]))
if not idem_result then
    return string.format('{"%s": %d}', 'code', 40035)
end

local type = tonumber(KEYS[1])

-- 限购校验（每单仅执行一次，顶层执行）：KEYS[9]=账户订单计数，ARGV[5]=限购数量，ARGV[6]=本次购票总数
-- 未预热（GET 为 nil）视为 0 不拦截首单，预热由登录/详情页异步完成；限购失败直接 return fail(50009)（fail 内部已 DEL 幂等标记）
local account_count_str = redis.call('GET', KEYS[9])
if account_count_str then
    local account_count = tonumber(account_count_str)
    local limit = tonumber(ARGV[5])
    if limit and limit > 0 and account_count + tonumber(ARGV[6]) > limit then
        return fail(50009)
    end
end
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
            return fail(40010)
        end
        local remain_number = tonumber(remain_number_str)
        -- 入参座位的票档数量大于缓存中获取相应票档数量，说明票档数量不足，直接返回
        if (count > remain_number) then
            return fail(40011)
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
                return fail(40001)
            end
            local seat_vo = cjson.decode(seat_vo_str)
            -- 如果从缓存查询的座位状态是锁定的，直接返回
            if (seat_vo.sellStatus == 2) then
                return fail(40002)
            end
            -- 如果从缓存查询的座位状态是已经售卖的，直接返回
            if (seat_vo.sellStatus == 3) then
                return fail(40003)
            end
            table.insert(purchase_seat_list,seat_vo)
            -- 入参座位价格累加
            total_seat_dto_price = total_seat_dto_price + seat_dto_price
            -- 缓存座位价格累加
            total_seat_vo_price = total_seat_vo_price + seat_vo.price
            if (total_seat_dto_price > total_seat_vo_price) then
                return fail(40008)
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
-- 经过以上的验证，说明座位和票档数量是够用的，下面开始真正的锁定座位和扣除票档数量操作
local seat_id_list = {}
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
-- 扣减成功后（每单仅执行一次）累加账户订单计数，计数单一归属 Lua，消费侧不再重复累加
redis.call('INCRBY', KEYS[9], tonumber(ARGV[6]))
-- 计数器 TTL 钉在固定值：既防止限购随 preload 短 TTL 过期重置，也避免无 TTL 永久累积漂移；
-- 活动结束后无交易 24h 自然过期（无需依赖节目重置清理）
redis.call('EXPIRE', KEYS[9], 86400)
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

-- 扣减后的余票 map（供应用层本地库存闸门精确追踪）
local remain_map = {}
for _, tc_record in ipairs(ticket_category_record_list) do
    remain_map[tostring(tc_record.ticketCategoryId)] = tc_record.afterAmount
end

return string.format('{"%s": %d, "%s": %s, "%s": %s}', 'code', 0, 'purchaseSeatList', cjson.encode(purchase_seat_list), 'remainMap', cjson.encode(remain_map))
