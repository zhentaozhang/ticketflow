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
  if ! curl -sf -X POST http://127.0.0.1:6086/program/data/preheat \
    -H "Content-Type: application/json" \
    -d '{"programId": 9999}' > /dev/null; then
    echo "  (data/preheat 不可用，降级用 reset/execute)"
    curl -sf -X POST http://127.0.0.1:6086/program/reset/execute \
      -H "Content-Type: application/json" \
      -d '{"programId": 9999}' > /dev/null || true
  fi
  curl -sf -X POST http://127.0.0.1:6086/test/reset \
    -H "Content-Type: application/json" \
    -d '{"testSendDto": "reset"}' > /dev/null || true
  sleep 3

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
    local lines expected
    lines=$(wc -l < "$SEATS_CSV")
    # 120000 数据行 + 1 表头行
    [ "$lines" -eq 120001 ] || return 1
    for pair in "901:20000" "902:20000" "903:20000" "904:20000" "905:20000" "906:10000" "907:10000"; do
      cat_id=${pair%%:*}
      expected=${pair##*:}
      actual=$(awk -F',' -v c="$cat_id" 'NR>1 && $2==c{n++} END{print n+0}' "$SEATS_CSV")
      [ "$actual" -eq "$expected" ] || return 1
    done
  }
  if [ ! -f "$SEATS_CSV" ] || ! validate_seats; then
    export_seats
    if ! validate_seats; then
      echo "ERROR: 座位导出校验失败（需 12 万行 + 表头，分档 901-905 各 2 万、906/907 各 1 万）"
      echo "      请确认 test-data.sql 已导入且 reset 已执行"
      exit 1
    fi
    echo "  (座位已导出: $(( $(wc -l < "$SEATS_CSV") - 1 )) 行)"
  else
    echo "  (座位 CSV 已存在且校验通过: $SEATS_CSV)"
  fi

  # 3. 落库对账：压测前计数
  echo "[3/7] 落库计数（压测前）..."
  ORDERS_BEFORE=$(count_orders)
  echo "  压测前 d_order 总量: $ORDERS_BEFORE"

  # 4. 执行 Gatling 压测
  echo "[4/7] 执行压测 (version=$VERSION, mode=$MODE, rate=$RATE, concurrency=$CONCURRENCY, duration=${DURATION}s)..."
  mvn -f "$PROJECT_DIR/pom.xml" gatling:test \
    -Dgatling.simulationClass=simulations.OrderBenchmark \
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
  ORDERS_AFTER=$(count_orders)
  echo "  压测后 d_order 总量: $ORDERS_AFTER"

  # 7. 失败分类汇总 + 结果 json
  echo "[7/7] 生成结果 json..."
  LATEST_REPORT=$(ls -td "$PROJECT_DIR/target/gatling/"*/ 2>/dev/null | head -1)
  STATS_JSON="${LATEST_REPORT}js/stats.json"
  FAILURE_JSON="$RESULT_DIR/failure-${ROUND_LABEL}.json"
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
