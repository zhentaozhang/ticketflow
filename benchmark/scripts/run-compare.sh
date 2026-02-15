#!/bin/bash
# =============================================
# V1-V4 版本对比压测
# 每个版本跑 3 个并发梯度
# =============================================
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=============================================="
echo " V1-V4 版本对比压测"
echo " 时间: $(date '+%Y-%m-%d %H:%M')"
echo "=============================================="

# 对比参数
CONCURRENCIES="50 100 200"
DURATION=20

for version in v1 v2 v3 v4; do
  echo ""
  echo "############### 版本: $version ###############"
  
  for concurrency in $CONCURRENCIES; do
    echo ""
    echo "--- 并发: $concurrency ---"
    bash "$SCRIPT_DIR/run-single.sh" "$version" "$concurrency" "$DURATION"
    
    # 版本内冷却
    echo "冷却 5 秒..."
    sleep 5
  done
  
  # 版本间冷却 + 缓存重置
  echo "版本 $version 完成，冷却 10 秒..."
  curl -sf -X POST http://127.0.0.1:6086/program/reset/execute \
    -H "Content-Type: application/json" \
    -d '{"programId": 9999}' > /dev/null || true
  sleep 10
done

echo ""
echo "=============================================="
echo " 版本对比完成!"
echo " 结果目录: benchmark/results/"
echo "=============================================="

# 打印汇总
echo ""
echo "=== 结果汇总 ==="
for f in "$SCRIPT_DIR/../results"/metrics-*.json; do
  if [ -f "$f" ]; then
    echo "--- $(basename "$f") ---"
    python3 -c "
import json
with open('$f') as fp:
    d = json.load(fp)
for k, v in d.items():
    print(f'  {k}: {v}')
" 2>/dev/null
  fi
done
