#!/usr/bin/env bash
# =============================================================================
# Lua 座位校验错误码自检
#
# 为什么需要它：Lua 脚本没有单测覆盖（跑集成测试要一整套中间件），
# 但它一旦写错就是"所有下单全挂"，所以改动后至少要能在真实 Redis 上过一遍。
#
# 做法：直接对着一个真 Redis EVAL 脚本，构造 5 种场景，断言返回码：
#   ① 正常扣减成功            → code=0
#   ② 座位被锁（不在未售集合） → code=40002
#   ③ 座位已售                → code=40003
#   ④ 座位根本不存在          → code=40001
#   ⑤ 余票不足                → code=40011
#
# 用法：REDIS_HOST=127.0.0.1 REDIS_PORT=6379 ./scripts/verify-lua-seat-codes.sh
# =============================================================================
set -euo pipefail

REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6379}"
SCRIPT="${SCRIPT:-ticketflow-server/ticketflow-program-service/src/main/resources/lua/programDataCreateOrderResolution.lua}"

PID=999999
TID=200
SEAT=3000
R="redis-cli -h ${REDIS_HOST} -p ${REDIS_PORT}"

REMAIN_KEY="tflow_luacheck_remain"
NO_SOLD_KEY="tflow_luacheck_no_sold_${PID}_${TID}"
LOCK_KEY="tflow_luacheck_lock_${PID}_${TID}"
SOLD_KEY="tflow_luacheck_sold_${PID}_${TID}"
RECORD_KEY="tflow_luacheck_record_${PID}"

cleanup() { $R del "$REMAIN_KEY" "$NO_SOLD_KEY" "$LOCK_KEY" "$SOLD_KEY" "$RECORD_KEY" >/dev/null; }
trap cleanup EXIT

# 参数：KEYS[1]=type 2=未售模板 3=锁定模板 4=programId 5=流水模板 6=标识 7=类型
KEYS=("1" "tflow_luacheck_no_sold_%s_%s" "tflow_luacheck_lock_%s_%s" "$PID" \
      "tflow_luacheck_record_%s" "REDUCE_1_1" "1")

TICKET_JSON="[{\"programTicketRemainNumberHashKey\":\"${REMAIN_KEY}\",\"ticketCategoryId\":${TID},\"ticketCount\":1}]"
# 注意：no_sold / lock / sold 三个 hash 的 field 值存的是“单个座位对象”（Java 侧是 cjson.encode(SeatVo)），
# 不是数组——数组会让脚本在取 price 时拿到 nil。
SEAT_VO_JSON="{\"id\":${SEAT},\"price\":100,\"sellStatus\":1,\"ticketCategoryId\":${TID},\"rowCode\":1,\"colCode\":1}"
SEAT_JSON="[{\"id\":${SEAT},\"price\":100,\"sellStatus\":1,\"ticketCategoryId\":${TID}}]"
SEAT_DATA_ESC=$(printf '%s' "$SEAT_JSON" | sed 's/"/\\"/g')
SEAT_DATA="[{\"seatNoSoldHashKey\":\"${NO_SOLD_KEY}\",\"seatLockHashKey\":\"${LOCK_KEY}\",\"seatSoldHashKey\":\"${SOLD_KEY}\",\"seatDataList\":\"${SEAT_DATA_ESC}\"}]"

run_case() {
  local name="$1" expect="$2"
  local result
  result=$($R --eval "$SCRIPT" "${KEYS[@]}" , "$TICKET_JSON" "$SEAT_DATA" "[4000]" | tr -d '\n')
  if [[ "$result" == *"\"code\": ${expect}"* ]]; then
    printf '  ✅ %-28s 期望 code=%-6s 实际: %s\n' "$name" "$expect" "$result"
    return 0
  fi
  printf '  ❌ %-28s 期望 code=%-6s 实际: %s\n' "$name" "$expect" "$result"
  return 1
}

fail=0
echo "对 Redis ${REDIS_HOST}:${REDIS_PORT} 执行 ${SCRIPT}"

# ① 正常：余票充足 + 座位在未售集合
$R del "$NO_SOLD_KEY" "$LOCK_KEY" "$SOLD_KEY" >/dev/null
$R hset "$REMAIN_KEY" "$TID" 10 >/dev/null
$R hset "$NO_SOLD_KEY" "$SEAT" "$SEAT_VO_JSON" >/dev/null
run_case "① 正常扣减成功" 0 || fail=1

# ② 座位被锁：不在未售集合，但在锁定集合
$R del "$NO_SOLD_KEY" >/dev/null
$R hset "$REMAIN_KEY" "$TID" 10 >/dev/null
$R hset "$LOCK_KEY" "$SEAT" "$SEAT_VO_JSON" >/dev/null
run_case "② 座位已被抢（在锁定集合）" 40002 || fail=1

# ③ 座位已售
$R del "$LOCK_KEY" >/dev/null
$R hset "$REMAIN_KEY" "$TID" 10 >/dev/null
$R hset "$SOLD_KEY" "$SEAT" "$SEAT_VO_JSON" >/dev/null
run_case "③ 座位已售" 40003 || fail=1

# ④ 座位根本不存在（三个集合都没有）
$R del "$SOLD_KEY" >/dev/null
$R hset "$REMAIN_KEY" "$TID" 10 >/dev/null
run_case "④ 座位不存在" 40001 || fail=1

# ⑤ 余票不足
$R hset "$REMAIN_KEY" "$TID" 0 >/dev/null
$R hset "$NO_SOLD_KEY" "$SEAT" "$SEAT_VO_JSON" >/dev/null
run_case "⑤ 余票不足" 40011 || fail=1

if [[ $fail -eq 0 ]]; then
  echo "全部通过"
else
  echo "有用例失败"
  exit 1
fi
