#!/usr/bin/env bash
# 优惠券秒杀压测阶梯（对齐 ticketflow benchmark 口径）：
#   开环恒定到达率逐档加压 → 每档独立新活动（库存充足）→ 冷却 lag=0 后统计落库
# 用法: bash benchmark/run-staircase.sh "40 80 120 160 200 300 400" 60
set -e
cd "$(dirname "$0")/.."

RATES="${1:-40 80 120 160 200 300 400}"
DURATION="${2:-60}"
mkdir -p benchmark-results

for r in $RATES; do
  echo "===== [$(date +%H:%M:%S)] rate=${r}/s duration=${DURATION}s ====="
  mvn -q exec:java -Dexec.mainClass=com.couponseckill.benchmark.BenchmarkMain \
    -Dexec.classpathScope=test \
    -Dexec.args="--rate ${r} --duration ${DURATION} --base $((2000000 + r * 1000)) --output benchmark-results/result-${r}.json"
done

echo "===== ALL DONE ====="
python3 benchmark/aggregate.py
