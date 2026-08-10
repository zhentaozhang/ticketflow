#!/bin/bash
# =============================================
# 阶梯到达率压测（开环）——找单机吞吐拐点
# 用法: bash scripts/run-staircase.sh <version> [rates] [duration]
# 示例: bash scripts/run-staircase.sh v4 "50 100 200 400 800" 60
# 每档调用 run-single.sh（内部已完成 reset + 座位导出 + 落库对账），数据档间隔离
# =============================================
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

VERSION=${1:-v4}
RATES=${2:-"50 100 200 400 800"}
DURATION=${3:-60}

echo "=============================================="
echo " 阶梯到达率压测（开环）"
echo " 版本: $VERSION  到达率: $RATES  时长: ${DURATION}s"
echo " 时间: $(date '+%Y-%m-%d %H:%M')"
echo "=============================================="

for rate in $RATES; do
  echo ""
  echo "############### 到达率: ${rate} QPS ###############"
  bash "$SCRIPT_DIR/run-single.sh" "$VERSION" 0 "$DURATION" openloop "$rate"
  echo "到达率 ${rate} 完成，冷却 5 秒..."
  sleep 5
done

echo ""
echo "=============================================="
echo " 阶梯压测完成！"
echo " 结果目录: benchmark/results/"
echo "=============================================="

echo ""
echo "=== 聚合对比报告 ==="
bash "$SCRIPT_DIR/compare-report.sh"
