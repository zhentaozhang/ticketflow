#!/bin/bash
# =============================================
# 阶梯到达率压测（开环）——找单机吞吐拐点
# 用法: bash scripts/run-staircase.sh <version> [rates] [duration] [rounds]
# 示例: bash scripts/run-staircase.sh v5 "50 100 200 400 800" 60 3
# 每档调用 run-single.sh（内部已完成 reset + 座位导出 + 冷却 lag=0 + 落库对账），数据档间隔离
# rounds: 每档重复轮数（默认 1；≥3 输出中位数/min-max）
# =============================================
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

VERSION=${1:-v4}
RATES=${2:-"50 100 200 400 800"}
DURATION=${3:-60}
ROUNDS=${4:-1}

echo "=============================================="
echo " 阶梯到达率压测（开环）"
echo " 版本: $VERSION  到达率: $RATES  时长: ${DURATION}s  轮数: $ROUNDS"
echo " 时间: $(date '+%Y-%m-%d %H:%M')"
echo "=============================================="

for rate in $RATES; do
  echo ""
  echo "############### 到达率: ${rate} QPS × $ROUNDS 轮 ###############"
  bash "$SCRIPT_DIR/run-single.sh" "$VERSION" 0 "$DURATION" openloop "$rate" "$ROUNDS"
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
