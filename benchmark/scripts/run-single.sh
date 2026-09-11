#!/bin/bash
# =============================================
# 单版本压测执行脚本（真并发/开环 + 选座 + 落库对账 + 失败汇总）
# 用法: bash scripts/run-single.sh <version> [concurrency] [duration] [mode] [rate] [rounds]
#   mode: closed=闭环真并发（默认）; openloop=开环到达率
#   rounds: 同参数重复轮数（默认 1；≥3 时 compare-report 输出中位数/min-max 区间）
# 示例: bash scripts/run-single.sh v5 30 60            # 闭环 30 并发 60s
#       bash scripts/run-single.sh v5 30 60 openloop 200 3   # 开环 200 QPS × 3 轮
# 前置: 服务已启动（6086），prometheus 已启动（9090），test-data.sql 已导入
# =============================================
set -e
set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/lib-orders.sh"

VERSION=${1:-v4}
CONCURRENCY=${2:-30}
DURATION=${3:-60}
MODE=${4:-closed}
RATE=${5:-$CONCURRENCY}
ROUNDS=${6:-1}
# 被测机地址：默认单机 127.0.0.1；局域网双机模式设 TARGET_HOST=<被测机IP>（压测机连被测机）
# export：collect-metrics.sh 等子进程脚本需要继承
export TARGET_HOST=${TARGET_HOST:-127.0.0.1}
BASE_URL="http://${TARGET_HOST}:6086"

# Redis 命令（单机走 docker exec；双机走 redis-cli -h 被测机）
redis_cmd() {
  if [ -z "$TARGET_HOST" ] || [ "$TARGET_HOST" = "127.0.0.1" ]; then
    docker exec ticketflow-redis redis-cli "$@" 2>/dev/null
  else
    redis-cli -h "$TARGET_HOST" -p 6379 "$@" 2>/dev/null
  fi
}
TIMESTAMP=$(date +%Y%m%d%H%M%S)
RESULT_DIR="$PROJECT_DIR/results"
mkdir -p "$RESULT_DIR"
SEATS_CSV="$PROJECT_DIR/src/test/resources/data/seats-9999.csv"
if [ "$MODE" = "openloop" ]; then
  LABEL="${VERSION}-r${RATE}-d${DURATION}"
else
  LABEL="${VERSION}-c${CONCURRENCY}-d${DURATION}"
fi

echo "=========================================="
echo "  版本压测"
echo "  版本:     $VERSION"
echo "  模式:     $MODE"
echo "  并发/到达率: ${CONCURRENCY}/${RATE}"
echo "  时长:     ${DURATION}s（开环满速 / 闭环 ramp 10s 后的稳态窗口）"
echo "  轮数:     $ROUNDS（同参数重复，compare-report 输出中位数/min-max）"
echo "  被测机:   $TARGET_HOST（双机模式时压测机需 mysql 客户端 + 被测机放行 3306/6086/9092/9090）"
echo "  开始时间: $(date '+%H:%M:%S')"
echo "=========================================="

# ---------------------------------------------------------------
# 配置快照（公平性前提：报告必须记录被测系统配置，防版本间配置漂移）
# 每个 result json 关联一份 snapshot json，含 git commit / 关键配置 / 数据规模
# ---------------------------------------------------------------
generate_snapshot() {
  local SNAPSHOT_FILE="$RESULT_DIR/snapshot-${LABEL}-${TIMESTAMP}.json"
  local GIT_SHA GIT_BRANCH PROG_PORT PROG_TOMCAT ORDER_TOMCAT KAFKA_PARTS SEAT_COUNT REDIS_MODE
  GIT_SHA=$(git -C "$PROJECT_DIR" rev-parse --short HEAD 2>/dev/null || echo "unknown")
  GIT_BRANCH=$(git -C "$PROJECT_DIR" branch --show-current 2>/dev/null || echo "unknown")
  PROG_PORT=$(grep -A1 '^server:' "$PROJECT_DIR/../ticketflow-server/ticketflow-program-service/src/main/resources/application.yml" 2>/dev/null | grep port | head -1 | awk '{print $2}' || echo "N/A")
  PROG_TOMCAT=$(grep -A8 '^  tomcat:' "$PROJECT_DIR/../ticketflow-server/ticketflow-program-service/src/main/resources/application.yml" 2>/dev/null | grep -A2 'threads:' | grep max | head -1 | awk '{print $2}' || echo "N/A")
  ORDER_TOMCAT=$(grep -A8 '^  tomcat:' "$PROJECT_DIR/../ticketflow-server/ticketflow-order-service/src/main/resources/application.yml" 2>/dev/null | grep -A2 'threads:' | grep max | head -1 | awk '{print $2}' || echo "N/A")
  # Kafka topic 分区数：运行时实测（优于读配置）
  KAFKA_PARTS=$(docker exec ticketflow-kafka /opt/bitnami/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 --describe --topic "ticketflow-create_order" 2>/dev/null \
    | awk '/PartitionCount/{print $2}' | head -1 || echo "N/A")
  [ -z "$KAFKA_PARTS" ] && KAFKA_PARTS="N/A"
  SEAT_COUNT=$(wc -l < "$SEATS_CSV" 2>/dev/null || echo "N/A")
  REDIS_MODE=$(redis-cli -h 127.0.0.1 -p 6379 info replication 2>/dev/null | grep '^role:' | cut -d: -f2 | tr -d '\r' || echo "N/A")

  cat > "$SNAPSHOT_FILE" <<EOF
{
  "git_commit": "$GIT_SHA",
  "git_branch": "$GIT_BRANCH",
  "generated_at": "$(date '+%Y-%m-%d %H:%M:%S')",
  "program_service": {"port": "$PROG_PORT", "tomcat_max_threads": "$PROG_TOMCAT"},
  "order_service": {"tomcat_max_threads": "$ORDER_TOMCAT"},
  "kafka_create_order_partitions": "$KAFKA_PARTS",
  "seat_count_csv": "$SEAT_COUNT",
  "redis_mode": "$REDIS_MODE",
  "load_generator": "same-machine (压测机=被测机，跨机压测另做)"
}
EOF
  echo "  配置快照已生成: $SNAPSHOT_FILE"
  echo "$SNAPSHOT_FILE"
}

# ---------------------------------------------------------------
# 冷却闭环：等 create_order 消费组 lag=0 后再统计落库（最多 90s）
# 异步版本（V4/V5）丢单判定必须基于消费排空后的落库数；V1-V3 同步路径 lag 恒 0 立即返回
# ---------------------------------------------------------------
wait_for_consumer_drain() {
  local max_wait=90 waited=0 lag=""
  while [ "$waited" -lt "$max_wait" ]; do
    lag=$(docker exec ticketflow-kafka /opt/bitnami/kafka/bin/kafka-consumer-groups.sh \
      --bootstrap-server localhost:9092 --describe --group create_order_data 2>/dev/null \
      | awk 'NR>1 && NF>=6 {s+=$6} END {print s+0}')
    if [ "$lag" = "0" ] || [ -z "$lag" ]; then
      break
    fi
    sleep 5
    waited=$((waited + 5))
  done
  echo "  消费排空等待 ${waited}s，最终 lag: ${lag:-0}"
  # lag 未归零时告警：落库差可能被低估
  if [ -n "$lag" ] && [ "$lag" != "0" ]; then
    echo "  ⚠️ 消费组 lag 未在 ${max_wait}s 内归零（${lag}），落库差可能低于真实值"
  fi
}

# ---------------------------------------------------------------
# PENDING 排空：等发送超时降级订单（ORDER_CREATE_PENDING 列表）裁决完成
# 端到端落库率统计必须等 PENDING 排空：列表非空时可能存在"消息实际已发出、订单即将落库"
# 或"未建单待回滚"的在途订单，过早计数会高估/低估落库差。V1-V3 同步路径无 PENDING，立即返回。
# 依赖 redis-cli（单机 127.0.0.1 / 双机 TARGET_HOST）；无 redis-cli 时告警跳过。
# ---------------------------------------------------------------
wait_for_pending_drain() {
  if ! command -v redis-cli >/dev/null 2>&1; then
    echo "  ⚠️ 无 redis-cli，跳过 PENDING 排空等待（落库差可能包含在途 PENDING 订单）"
    return
  fi
  local PENDING_KEY="ticketflow-d_mai_order_create_pending_9999"
  local max_wait=90 waited=0 len=""
  while [ "$waited" -lt "$max_wait" ]; do
    len=$(redis-cli -h "$TARGET_HOST" -p 6379 LLEN "$PENDING_KEY" 2>/dev/null | tr -d '\r')
    if [ -z "$len" ] || [ "$len" = "0" ]; then
      break
    fi
    sleep 5
    waited=$((waited + 5))
  done
  echo "  PENDING 排空等待 ${waited}s，最终 PENDING 数: ${len:-0}"
  if [ -n "$len" ] && [ "$len" != "0" ]; then
    echo "  ⚠️ PENDING 未在 ${max_wait}s 内排空（${len}），落库差可能仍包含在途订单"
  fi
}

# ---------------------------------------------------------------
# 单轮压测（round 从 1 开始；每轮内部完成 reset+预热+压测+冷却+落库统计）
# ---------------------------------------------------------------
run_round() {
  local ROUND=$1
  local ROUND_LABEL="${LABEL}-round${ROUND}"
  echo ""
  echo "========================== 轮次 $ROUND/$ROUNDS =========================="

  # 1. 前置数据：允许选座 + 预热（DB 重置 + 节目详情/座位/余票全量入缓存）
  #    dataPreheat 内部先 resetExecute（重置 DB + 清缓存）再预热座位分辩率/余票，
  #    避免压测首波请求在锁内触发 DB 冷加载（拉长锁持有时间 → 70005 暴增）
  echo "[1/7] 前置数据: permit_choose_seat=1 + preheat..."
  MYSQL -e "UPDATE ticketflow_program_1.d_program_1 SET permit_choose_seat=1 WHERE id=9999;"
  # 先清掉上一轮/上次数据重置残留的座位与余票缓存：
  # data/preheat 只补“缺失”的 key，已存在的 hash 不会覆盖。
  # 若座位数据被重建（test-data.sql 会 DELETE + 重新 INSERT，座位 id 整批换掉），
  # 而缓存里还是旧 id，则预热会“看起来成功”（key 存在），但后续每个请求都以
  # 40001（座位不存在）+ p50≈0ms 秒失败——极容易被当成性能结论（本项目实际踩过）。
  clear_round_caches() {
    local pattern keys
    for pattern in \
      "ticketflow-d_mai_program_seat_no_sold_resolution_hash_9999_*" \
      "ticketflow-d_mai_program_seat_lock_resolution_hash_9999_*" \
      "ticketflow-d_mai_program_seat_sold_resolution_hash_9999_*" \
      "ticketflow-d_mai_program_ticket_remain_number_hash_resolution_9999_*"; do
      keys=$(redis_cmd --scan --pattern "$pattern" | tr '\r' ' ')
      [ -n "$keys" ] && redis_cmd del $keys > /dev/null
    done
    return 0
  }
  clear_round_caches
  if ! curl -sf -X POST "$BASE_URL/program/data/preheat" \
    -H "Content-Type: application/json" \
    -d '{"programId": 9999}' > /dev/null; then
    echo "  (data/preheat 不可用，降级用 reset/execute)"
    curl -sf -X POST "$BASE_URL/program/reset/execute" \
      -H "Content-Type: application/json" \
      -d '{"programId": 9999}' > /dev/null || true
  fi
  curl -sf -X POST "$BASE_URL/test/reset" \
    -H "Content-Type: application/json" \
    -d '{"testSendDto": "reset"}' > /dev/null || true
  # 不能盲等：preheat 内部先 resetExecute（清缓存）再重建座位/余票，重建需要几秒；
  # 固定 sleep 会在重建未完成时开压——那一轮所有请求以 40001 + p50≈0ms 秒失败，
  # 看起来只是“成功率 0%”，极易被当成性能结论。改为轮询直到 7 个座位档全部就绪。
  wait_for_preheat() {
    # 判据用“内容”而不是“key 存在”：preheat 要把 7 个票档共 12 万个座位写进 Redis，
    # 实测要几十秒；只看 key 会在写到一半时误判为就绪，那一轮的成功率就不是真实值。
    local waited=0 total cat_id hlen
    while [ "$waited" -lt 240 ]; do
      total=0
      for cat_id in 901 902 903 904 905 906 907; do
        hlen=$(redis_cmd hlen "ticketflow-d_mai_program_seat_no_sold_resolution_hash_9999_${cat_id}" | tr -d '\r')
        if [ -n "$hlen" ] && [ "$hlen" -gt 0 ] 2>/dev/null; then
          total=$((total + hlen))
        fi
      done
      [ "$total" -ge 120000 ] && { echo "  (预热就绪: 座位缓存 ${total} 个)"; return 0; }
      # 每 15s 重试一次 preheat：服务刚重启、或依赖（base-data/customize）未就绪时，
      # 首次调用会静默失败（HTTP 200 + data:false，什么都不预热），只等不重试会白等满超时
      if [ "$waited" -gt 0 ] && [ $((waited % 15)) -eq 0 ]; then
        curl -sf -X POST "$BASE_URL/program/data/preheat" -H "Content-Type: application/json" \
          -d '{"programId": 9999}' > /dev/null || true
      fi
      sleep 3
      waited=$((waited + 3))
    done
    echo "  !! 预热未就绪：240s 后座位缓存仅 ${total} 个（应 120000；先查 program/base-data/customize 是否都活着、Nacos 实例是否齐）"
    return 1
  }
  wait_for_preheat || exit 1

  # 2. 座位导出 + 校验（12 万行 + 表头：901-905 各 2 万、906/907 各 1 万；缺档报错不静默降级）
  echo "[2/7] 座位导出与校验..."
  export_seats() {
    mkdir -p "$PROJECT_DIR/src/test/resources/data"
    # 注意：Gatling CSV feeder 第一行是表头 → 必须输出 header 行（列名与 SeatPool 的 key 一致）
    {
      echo "seatId,ticketCategoryId,price,rowCode,colCode"
      MYSQL -e \
        "SELECT id, ticket_category_id, price, row_code, col_code FROM ticketflow_program_1.d_seat_1 \
         WHERE program_id=9999 AND sell_status=1 ORDER BY ticket_category_id, row_code, col_code;" \
        | awk '{print $1","$2","$3","$4","$5}'
    } > "$SEATS_CSV"
  }
  validate_seats() {
    # 只校验“可用”：表头 + 每个票档至少 1 个座位 + 总量下限。
    # 不再死磕 12 万：一轮跑完会消耗座位（DB 的 sell_status 不会自动恢复），
    # 真正的正确性约束是“CSV 与 Redis 未售集合一致”（见 validate_seats_in_redis）。
    # 若要每轮都是干净的全量座位池，请在轮与轮之间重跑 data/test-data.sql（见 README）。
    local lines cat_id actual
    lines=$(wc -l < "$SEATS_CSV")
    [ "$lines" -ge 1001 ] || return 1
    for cat_id in 901 902 903 904 905 906 907; do
      actual=$(awk -F',' -v c="$cat_id" 'NR>1 && $2==c{n++} END{print n+0}' "$SEATS_CSV")
      [ "$actual" -ge 1 ] || return 1
    done
  }
  # 交叉校验：导出的座位必须真实存在于 Redis 未售集合（选座路径的数据源）；
  # 座位 id 被重置换掉时，只查行数/分档是发现不了的
  validate_seats_in_redis() {
    local program_id=9999 cat_id seat_id checked=0 hit
    for cat_id in 901 902 903 904 905 906 907; do
      seat_id=$(awk -F',' -v c="$cat_id" 'NR>1 && $2==c{print $1; exit}' "$SEATS_CSV")
      [ -z "$seat_id" ] && continue
      checked=$((checked + 1))
      hit=$(redis_cmd hexists "ticketflow-d_mai_program_seat_no_sold_resolution_hash_${program_id}_${cat_id}" "$seat_id" | tr -d '\r')
      [ "$hit" = "1" ] || {
        echo "  !! 座位 $seat_id（票档 $cat_id）不在 Redis 未售集合中"
        return 1
      }
    done
    [ "$checked" -gt 0 ]
  }
  export_seats
  if ! validate_seats; then
    echo "ERROR: 座位导出校验失败（需要表头 + 每个票档至少 1 个可用座位）"
    echo "      请确认 test-data.sql 已导入（重置座位数据）"
    exit 1
  fi
  if ! validate_seats_in_redis; then
    echo "ERROR: 座位交叉校验失败——CSV 里的座位在 Redis 未售集合中不存在"
    echo "      通常是节目数据被重置（座位 id 整批变更）导致 CSV 过期"
    exit 1
  fi
  echo "  (座位已导出并与 Redis 交叉校验通过: $(( $(wc -l < "$SEATS_CSV") - 1 )) 行)"

  # 3. 落库对账：压测前计数
  echo "[3/7] 落库计数（压测前）..."
  ORDERS_BEFORE=$(count_orders)
  echo "  压测前 d_order 总量: $ORDERS_BEFORE"

  # 4. 执行 Gatling 压测
  echo "[4/7] 执行压测 (version=$VERSION, mode=$MODE, rate=$RATE, concurrency=$CONCURRENCY, duration=${DURATION}s)..."
  mvn -f "$PROJECT_DIR/pom.xml" gatling:test \
    -Dgatling.simulationClass=simulations.OrderBenchmark \
    -DbaseUrl="$BASE_URL" \
    -DappVersion="$VERSION" \
    -Dmode="$MODE" \
    -Drate="$RATE" \
    -Dconcurrency="$CONCURRENCY" \
    -Dduration="$DURATION" \
    -DresultsDir="$RESULT_DIR" 2>&1 | tee "$RESULT_DIR/gatling-${ROUND_LABEL}-${TIMESTAMP}.log"
  # set -e -o pipefail 下 mvn 失败管道即失败退出，无需额外检查
  # 注意：Gatling 报告固定落 target/gatling/（gatling.resultsDirectory 非插件属性，不传）

  # 5. 指标采集（压测结束立即采，避免 mq 等待期污染瞬时 gauge）
  echo "[5/7] 采集压测指标..."
  bash "$SCRIPT_DIR/collect-metrics.sh" "$ROUND_LABEL"

  # 6. 落库对账：等 mq 消费完（lag=0）再计数
  echo "[6/7] 冷却：等 create_order 消费组 lag=0 后落库计数..."
  wait_for_consumer_drain
  # V4/V5 异步路径：等 PENDING 发送超时订单裁决完成后再计数（V1-V3 无 PENDING 立即返回）
  wait_for_pending_drain
  ORDERS_AFTER=$(count_orders)
  echo "  压测后 d_order 总量: $ORDERS_AFTER"

  # 7. 失败分类汇总 + 结果 json
  echo "[7/7] 生成结果 json..."
  LATEST_REPORT=$(ls -td "$PROJECT_DIR/target/gatling/"*/ 2>/dev/null | head -1)
  STATS_JSON="${LATEST_REPORT}js/stats.json"
  # OrderResultCounter.dump 的文件名不带 round 后缀（openloop: v-r{d}-d{d}；closed: v-c{c}-d{d}）
  if [ "$MODE" = "openloop" ]; then
    FAILURE_JSON="$RESULT_DIR/failure-${VERSION}-r${RATE}-d${DURATION}.json"
  else
    FAILURE_JSON="$RESULT_DIR/failure-${VERSION}-c${CONCURRENCY}-d${DURATION}.json"
  fi
  METRICS_JSON=$(ls -t "$RESULT_DIR"/metrics-${ROUND_LABEL}-*.json 2>/dev/null | head -1)
  SIM_LOG="${LATEST_REPORT}simulation.log"

  python3 "$PROJECT_DIR/scripts/build-result.py" \
    --label "$ROUND_LABEL" \
    --version "$VERSION" \
    --mode "$MODE" \
    --concurrency "$CONCURRENCY" \
    --rate "$RATE" \
    --duration "$DURATION" \
    --stats "$STATS_JSON" \
    --failure "$FAILURE_JSON" \
    --sim-log "$SIM_LOG" \
    --orders-before "$ORDERS_BEFORE" \
    --orders-after "$ORDERS_AFTER" \
    --metrics "$METRICS_JSON" \
    --snapshot "$SNAPSHOT_FILE" \
    --out "$RESULT_DIR/result-${ROUND_LABEL}-${TIMESTAMP}.json"

  echo ""
  echo "=== 轮次 $ROUND/$ROUNDS 完成: $VERSION @ $CONCURRENCY 并发 ==="
}

# 配置快照只生成一次（不随轮次变化）
SNAPSHOT_FILE=$(generate_snapshot)

# 多轮执行：每轮独立 reset+预热+压测+冷却+落库统计
for ((i = 1; i <= ROUNDS; i++)); do
  run_round "$i"
done

echo ""
echo "=============================================="
echo " 全部 $ROUNDS 轮完成: $VERSION ($MODE $RATE)"
echo " 结果目录: $RESULT_DIR"
echo " 多轮聚合: bash scripts/compare-report.sh"
echo "=============================================="
