#!/bin/bash
# =============================================
# 单版本压测执行脚本（真并发 + 选座 + 落库对账 + 失败汇总）
# 用法: bash scripts/run-single.sh <version> [concurrency] [duration]
# 示例: bash scripts/run-single.sh v4 30 20
# 前置: 服务已启动（6086），prometheus 已启动（9090），test-data.sql 已导入
# =============================================
set -e
set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/lib-orders.sh"

VERSION=${1:-v4}
CONCURRENCY=${2:-30}
DURATION=${3:-20}
TIMESTAMP=$(date +%Y%m%d%H%M%S)
RESULT_DIR="$PROJECT_DIR/results"
mkdir -p "$RESULT_DIR"
SEATS_CSV="$PROJECT_DIR/src/test/resources/data/seats-9999.csv"
LABEL="${VERSION}-c${CONCURRENCY}-d${DURATION}"

echo "=========================================="
echo "  版本对比压测"
echo "  版本:     $VERSION"
echo "  并发:     $CONCURRENCY"
echo "  时长:     ${DURATION}s（ramp 10s 后的稳态窗口）"
echo "  开始时间: $(date '+%H:%M:%S')"
echo "=========================================="

# 1. 前置数据：允许选座 + 重置（清缓存使 permit_choose_seat 生效）
echo "[1/7] 前置数据: permit_choose_seat=1 + reset..."
MYSQL -e "UPDATE ticketflow_program_1.d_program_1 SET permit_choose_seat=1 WHERE id=9999;"
curl -sf -X POST http://127.0.0.1:6086/program/reset/execute \
  -H "Content-Type: application/json" \
  -d '{"programId": 9999}' > /dev/null || echo "  (reset 接口不可用，跳过)"
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
echo "[4/7] 执行压测 (version=$VERSION, concurrency=$CONCURRENCY, duration=${DURATION}s)..."
mvn -f "$PROJECT_DIR/pom.xml" gatling:test \
  -Dgatling.simulationClass=simulations.OrderBenchmark \
  -DappVersion="$VERSION" \
  -Dconcurrency="$CONCURRENCY" \
  -Dduration="$DURATION" \
  -DresultsDir="$RESULT_DIR" 2>&1 | tee "$RESULT_DIR/gatling-${LABEL}-${TIMESTAMP}.log"
# set -e -o pipefail 下 mvn 失败管道即失败退出，无需额外检查
# 注意：Gatling 报告固定落 target/gatling/（gatling.resultsDirectory 非插件属性，不传）

# 5. 指标采集（压测结束立即采，避免 mq 等待期污染瞬时 gauge）
echo "[5/7] 采集压测指标..."
bash "$SCRIPT_DIR/collect-metrics.sh" "$LABEL"

# 6. 落库对账：等 mq 消费完再计数
echo "[6/7] 等待 mq 消费 (30s) 后落库计数..."
sleep 30
ORDERS_AFTER=$(count_orders)
echo "  压测后 d_order 总量: $ORDERS_AFTER"

# 7. 失败分类汇总 + 结果 json
echo "[7/7] 生成结果 json..."
LATEST_REPORT=$(ls -td "$PROJECT_DIR/target/gatling/"*/ 2>/dev/null | head -1)
STATS_JSON="${LATEST_REPORT}js/stats.json"
FAILURE_JSON="$RESULT_DIR/failure-${LABEL}.json"
METRICS_JSON=$(ls -t "$RESULT_DIR"/metrics-${LABEL}-*.json 2>/dev/null | head -1)
SIM_LOG="${LATEST_REPORT}simulation.log"

python3 "$PROJECT_DIR/scripts/build-result.py" \
  --label "$LABEL" \
  --version "$VERSION" \
  --concurrency "$CONCURRENCY" \
  --duration "$DURATION" \
  --stats "$STATS_JSON" \
  --failure "$FAILURE_JSON" \
  --sim-log "$SIM_LOG" \
  --orders-before "$ORDERS_BEFORE" \
  --orders-after "$ORDERS_AFTER" \
  --metrics "$METRICS_JSON" \
  --out "$RESULT_DIR/result-${LABEL}-${TIMESTAMP}.json"

echo ""
echo "=== 完成: $VERSION @ $CONCURRENCY 并发 ==="
