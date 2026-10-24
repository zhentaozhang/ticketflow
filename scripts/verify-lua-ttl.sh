#!/usr/bin/env bash
# =============================================================================
# Lua 派生缓存 TTL 自检（P1-34/P1-35）
#
# 为什么需要它：座位/余票/流水三类派生 hash 由 Lua 写入，Java 侧的单测只能验参数
# 是否传对，验不了 Redis 里是不是真的设了期。历史上这些 key 一个 EXPIRE 都没有：
#   · HDEL 把 hash 清空后整个 key 消失，随后 HMSET/HINCRBY 重建的 key 没有 TTL → 永久驻留
#   · 余票 hash 过期后取消订单会让 HINCRBY 从 0 起算 → 库存被静默改写
# 所以每次改 Lua 都要在真 Redis 上过一遍。
#
# 断言四件事：
#   ① 新建 key 会补上有效期（座位/余票 1800s、流水 604800s）
#   ② 已有 TTL 不被延长（helper 只在 ttl < 0 时设期）
#   ③ TTL 参数缺失时退化为安全缺省值，而不是不设期
#   ④ 三个脚本都能被 Redis 正常加载（语法/参数契约没写崩）
#
# 用法：REDIS_HOST=127.0.0.1 REDIS_PORT=6379 REDIS_PASSWORD=ticketflow ./scripts/verify-lua-ttl.sh
# =============================================================================
set -euo pipefail

REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_PASSWORD="${REDIS_PASSWORD:-ticketflow}"
R="redis-cli -h ${REDIS_HOST} -p ${REDIS_PORT} -a ${REDIS_PASSWORD} --no-auth-warning"

ORDER_LUA="ticketflow-server/ticketflow-order-service/src/main/resources/lua/OrderProgramDataResolution.lua"
PROGRAM_RESOLUTION_LUA="ticketflow-server/ticketflow-program-service/src/main/resources/lua/programDataResolution.lua"
PROGRAM_CREATE_LUA="ticketflow-server/ticketflow-program-service/src/main/resources/lua/programDataCreateOrderResolution.lua"

PID=999999
TID=200
SEAT=3000
PREFIX="tflow_ttlcheck"
LOCK_KEY="${PREFIX}_lock_${PID}_${TID}"
NO_SOLD_KEY="${PREFIX}_no_sold_${PID}_${TID}"
SOLD_KEY="${PREFIX}_sold_${PID}_${TID}"
REMAIN_KEY="${PREFIX}_remain_${PID}_${TID}"
RECORD_TEMPLATE="${PREFIX}_record_%s"
RECORD_KEY="${PREFIX}_record_${PID}"

cleanup() {
  $R del "$LOCK_KEY" "$NO_SOLD_KEY" "$SOLD_KEY" "$REMAIN_KEY" "$RECORD_KEY" >/dev/null || true
}
trap cleanup EXIT

fail=0
pass() { printf '  ✅ %s\n' "$1"; }
check() {
  local name="$1" actual="$2" expect="$3"
  if [[ "$actual" == "$expect" ]]; then pass "$name ($actual)"; else
    printf '  ❌ %s 期望 %s 实际 %s\n' "$name" "$expect" "$actual"; fail=1
  fi
}

# ---------- ① 语法：三个脚本都要能被 Redis 加载 ----------
echo "脚本加载检查"
for lua in "$ORDER_LUA" "$PROGRAM_RESOLUTION_LUA" "$PROGRAM_CREATE_LUA"; do
  if $R script load "$(cat "$lua")" >/dev/null 2>&1; then
    pass "加载 $(basename "$lua")"
  else
    printf '  ❌ 加载失败 %s\n' "$lua"; fail=1
  fi
done

# ---------- ② 订单侧：取消订单、key 全都不存在 → 三类 hash 都要有 TTL ----------
echo "OrderProgramDataResolution.lua：新建 key 补设期"
cleanup
# 余票 hash 必须先存在且可读：脚本会先 HGET 出来算 beforeAmount，
# 拿不到就直接报 Lua 错误（这也是"余票 key 消失"不会静默改写库存的原因）。
$R hset "$REMAIN_KEY" "$TID" 10 >/dev/null
SEAT_VO_JSON="{\"id\":${SEAT},\"sellStatus\":1,\"ticketCategoryId\":${TID}}"
UNLOCK_JSON="[{\"programSeatLockHashKey\":\"${LOCK_KEY}\",\"unLockSeatIdList\":[\"${SEAT}\"]}]"
ADD_SEAT_JSON="[{\"seatHashKeyAdd\":\"${NO_SOLD_KEY}\",\"seatDataList\":[\"${SEAT}\",\"$(printf '%s' "$SEAT_VO_JSON" | sed 's/"/\\"/g')\"]}]"
TICKET_JSON="[{\"programTicketRemainNumberHashKey\":\"${REMAIN_KEY}\",\"ticketCategoryId\":\"${TID}\",\"count\":1}]"
SEAT_USER_JSON="[{\"seatId\":${SEAT},\"ticketUserId\":2000}]"

KEYS_ORDER=(2 "$PID" "$RECORD_TEMPLATE" "INCREASE_1_1" "1")
$R --eval "$ORDER_LUA" "${KEYS_ORDER[@]}" , "$UNLOCK_JSON" "$ADD_SEAT_JSON" "$TICKET_JSON" "$SEAT_USER_JSON" 1800 604800 >/dev/null

check "未售座位 hash 有效期" "$($R ttl "$NO_SOLD_KEY")" "1800"
check "余票 hash 有效期" "$($R ttl "$REMAIN_KEY")" "1800"
check "流水 hash 有效期" "$($R ttl "$RECORD_KEY")" "604800"

# ---------- ③ 已有 TTL 不被延长 ----------
echo "OrderProgramDataResolution.lua：已有 TTL 不延长"
cleanup
$R hset "$NO_SOLD_KEY" "$SEAT" "$SEAT_VO_JSON" >/dev/null
$R expire "$NO_SOLD_KEY" 500 >/dev/null
$R hset "$REMAIN_KEY" "$TID" 10 >/dev/null
$R expire "$REMAIN_KEY" 500 >/dev/null
$R hset "$RECORD_KEY" old "1" >/dev/null
$R expire "$RECORD_KEY" 500 >/dev/null

$R --eval "$ORDER_LUA" "${KEYS_ORDER[@]}" , "$UNLOCK_JSON" "$ADD_SEAT_JSON" "$TICKET_JSON" "$SEAT_USER_JSON" 1800 604800 >/dev/null

for k in "$NO_SOLD_KEY" "$REMAIN_KEY" "$RECORD_KEY"; do
  ttl="$($R ttl "$k")"
  if [[ "$ttl" -gt 0 && "$ttl" -le 500 ]]; then pass "已有 TTL 未被延长 $(basename "$k") ($ttl)"; else
    printf '  ❌ 已有 TTL 被改写 %s 实际 %s\n' "$k" "$ttl"; fail=1
  fi
done

# ---------- ④ TTL 参数缺失 → 安全缺省 ----------
echo "OrderProgramDataResolution.lua：TTL 参数缺失时退化"
cleanup
$R hset "$REMAIN_KEY" "$TID" 10 >/dev/null
$R --eval "$ORDER_LUA" "${KEYS_ORDER[@]}" , "$UNLOCK_JSON" "$ADD_SEAT_JSON" "$TICKET_JSON" "$SEAT_USER_JSON" "" "" >/dev/null
check "缺省座位/余票有效期" "$($R ttl "$REMAIN_KEY")" "1800"
check "缺省流水有效期" "$($R ttl "$RECORD_KEY")" "604800"

# ---------- ⑤ 节目编辑链路 ----------
echo "programDataResolution.lua：新建 key 补设期"
cleanup
$R --eval "$PROGRAM_RESOLUTION_LUA" "#" ,   "[{\"programTicketRemainNumberHashKey\":\"${REMAIN_KEY}\",\"ticketCategoryId\":\"${TID}\",\"count\":2}]"   "[{\"seatHashKeyDel\":\"${LOCK_KEY}\",\"seatIdList\":[\"${SEAT}\"]}]"   "[{\"seatHashKeyAdd\":\"${NO_SOLD_KEY}\",\"seatDataList\":[\"${SEAT}\",\"$(printf '%s' "$SEAT_VO_JSON" | sed 's/"/\\"/g')\"]}]"   1800 >/dev/null
check "余票 hash 有效期" "$($R ttl "$REMAIN_KEY")" "1800"
check "座位 hash 有效期" "$($R ttl "$NO_SOLD_KEY")" "1800"

$R expire "$NO_SOLD_KEY" 400 >/dev/null
$R --eval "$PROGRAM_RESOLUTION_LUA" "#" ,   "[{\"programTicketRemainNumberHashKey\":\"${REMAIN_KEY}\",\"ticketCategoryId\":\"${TID}\",\"count\":2}]"   "[{\"seatHashKeyDel\":\"${LOCK_KEY}\",\"seatIdList\":[\"${SEAT}\"]}]"   "[{\"seatHashKeyAdd\":\"${NO_SOLD_KEY}\",\"seatDataList\":[\"${SEAT}\",\"$(printf '%s' "$SEAT_VO_JSON" | sed 's/"/\\"/g')\"]}]"   1800 >/dev/null
ttl="$($R ttl "$NO_SOLD_KEY")"
if [[ "$ttl" -gt 0 && "$ttl" -le 400 ]]; then pass "已有 TTL 未被延长 ($ttl)"; else
  printf '  ❌ 已有 TTL 被改写 实际 %s\n' "$ttl"; fail=1
fi

if [[ $fail -eq 0 ]]; then
  echo "全部通过"
else
  echo "有用例失败"
  exit 1
fi
