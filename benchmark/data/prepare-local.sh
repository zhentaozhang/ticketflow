#!/bin/bash
# =============================================
# 压测数据准备脚本（幂等，可重复执行）
# 在本地 Mac 上运行，前提是 Docker 服务已启动
# =============================================
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "=== 1. 检查 Docker 服务 ==="
for name in ticketflow-mysql ticketflow-redis ticketflow-nacos ticketflow-kafka; do
  if ! docker ps --format '{{.Names}}' | grep -q "$name"; then
    echo "ERROR: $name 未运行，请先启动 docker-compose"
    exit 1
  fi
done
echo "Docker 服务运行正常"

echo ""
echo "=== 2. 注入压测数据 ==="
# 用 docker exec 执行 MySQL 命令，避免本地 mysql 客户端版本不兼容
docker exec -i ticketflow-mysql mysql -uroot -proot < "$SCRIPT_DIR/test-data.sql"
echo "压测数据注入完成"

echo ""
echo "=== 3. 生成 CSV 压测数据 ==="
CSV_DIR="$PROJECT_DIR/benchmark/data"
# 如果已有 CSV 且数据量足够，跳过
if [ -f "$CSV_DIR/test-data.csv" ]; then
  LINES=$(wc -l < "$CSV_DIR/test-data.csv")
  if [ "$LINES" -ge 1000 ]; then
    echo "CSV 文件已存在 ($LINES 行)，跳过生成"
  else
    echo "CSV 文件过小，重新生成..."
    # 生成 7 个票档各 2000 个请求 = 14000 行
    python3 -c "
import csv, random
with open('$CSV_DIR/test-data.csv', 'w', newline='') as f:
    w = csv.writer(f)
    w.writerow(['programId', 'ticketCategoryId', 'userId'])
    for cat in [901, 902, 903, 904, 905, 906, 907]:
        for _ in range(2000):
            w.writerow([9999, cat, random.randint(1, 5000)])
"
    echo "CSV 生成完成"
  fi
else
  echo "CSV 不存在，生成..."
  python3 -c "
import csv
with open('$CSV_DIR/test-data.csv', 'w', newline='') as f:
    w = csv.writer(f)
    w.writerow(['programId', 'ticketCategoryId', 'userId'])
    for cat in [901, 902, 903, 904, 905, 906, 907]:
        for _ in range(2000):
            import random
            w.writerow([9999, cat, random.randint(1, 5000)])
"
  echo "CSV 生成完成"
fi

echo ""
echo "=== 4. 等待服务就绪 ==="
echo "检查微服务是否可访问..."

for port in 6085 6082 8081 6086; do
  for i in $(seq 1 30); do
    if curl -sf "http://127.0.0.1:$port/actuator/health" > /dev/null 2>&1; then
      echo "  port $port OK"
      break
    fi
    if [ "$i" -eq 30 ]; then
      echo "  port $port 未就绪，继续执行..."
    fi
    sleep 2
  done
done

echo ""
echo "=== 5. 预热缓存 ==="
# 调用节目详情加载到缓存
curl -sf -X POST http://127.0.0.1:6086/program/detail \
  -H "Content-Type: application/json" \
  -d '{"id":9999}' > /dev/null && echo "节目详情已预热"

# 调用数据预热接口（ProgramDataPreheatDto 字段为 programId）
curl -sf -X POST http://127.0.0.1:6086/program/data/preheat \
  -H "Content-Type: application/json" \
  -d '{"programId":9999}' > /dev/null && echo "数据预热完成"

# 重置 BloomFilter 加载节目数据
curl -sf -X POST http://127.0.0.1:6086/program/reset/execute \
  -H "Content-Type: application/json" \
  -d '{"programId":9999}' > /dev/null && echo "缓存重置完成"

echo ""
echo "=== 数据准备全部完成 ==="
echo "节目: 9999 (【压测专用】群星演唱会)"
echo "票档: 901-907 (共 120000 张)"
echo "座位: 120000 个 (与票档余票一致)"
echo "用户: 5000 个 (ID 1-5000)"
echo ""
