#!/bin/bash
# =============================================
# 单版本压测执行脚本
# 用法: bash scripts/run-single.sh <version> [concurrency] [duration]
# 示例: bash scripts/run-single.sh v4 100 30
# =============================================
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

VERSION=${1:-v4}
CONCURRENCY=${2:-50}
DURATION=${3:-30}
TIMESTAMP=$(date +%Y%m%d%H%M%S)

echo "=========================================="
echo "  版本对比压测"
echo "  版本:     $VERSION"
echo "  并发:     $CONCURRENCY"
echo "  时长:     ${DURATION}s"
echo "  开始时间: $(date '+%H:%M:%S')"
echo "=========================================="

# 1. 重置缓存到初始状态
echo "[1/4] 重置缓存..."
curl -sf -X POST http://127.0.0.1:6086/program/reset/execute \
  -H "Content-Type: application/json" \
  -d '{"programId": 9999}' > /dev/null || echo "  (reset 接口不可用，跳过)"

# 2. 重置计数器
echo "[2/4] 重置计数器..."
curl -sf -X POST http://127.0.0.1:6086/test/reset \
  -H "Content-Type: application/json" \
  -d '{"testSendDto": "reset"}' > /dev/null || true

sleep 3

# 3. 执行 Gatling 压测
echo "[3/4] 执行压测 (version=$VERSION, concurrency=$CONCURRENCY, duration=${DURATION}s)..."
mvn -f "$PROJECT_DIR/pom.xml" gatling:test \
  -Dgatling.simulationClass=simulations.OrderBenchmark \
  -DappVersion="$VERSION" \
  -DtargetQps="$CONCURRENCY" \
  -Dduration="$DURATION" \
  -Dgatling.resultsDirectory="$PROJECT_DIR/results" 2>&1 | tee "$PROJECT_DIR/results/gatling-${VERSION}-${TIMESTAMP}.log"

# 4. 采集指标
echo "[4/4] 采集压测指标..."
bash "$SCRIPT_DIR/collect-metrics.sh" "${VERSION}-c${CONCURRENCY}-d${DURATION}"

# 5. 找到 Gatling 报告
LATEST_REPORT=$(ls -td "$PROJECT_DIR/target/gatling/"*/ 2>/dev/null | head -1)
if [ -n "$LATEST_REPORT" ]; then
  echo ""
  echo "Gatling 报告: $LATEST_REPORT"
fi

echo ""
echo "=== 完成: $VERSION @ $CONCURRENCY 并发 ==="
echo ""
