#!/bin/bash
# =============================================
# 压测指标采集脚本
# 从 Prometheus 抓取关键指标，保存到 results 目录
# =============================================
set -e

VERSION=${1:-unknown}
TIMESTAMP=$(date +%Y%m%d%H%M%S)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RESULT_DIR="$PROJECT_DIR/results"
mkdir -p "$RESULT_DIR"

# 从版本标签（如 v4-c30-d20）解析压测时长，rate 窗口覆盖整个压测期（含 ramp）
DURATION=$(echo "$VERSION" | sed -n 's/.*-d\([0-9]*\).*/\1/p')
[ -z "$DURATION" ] && DURATION=30
RATE_WINDOW=$((DURATION + 30))s

OUTPUT_FILE="$RESULT_DIR/metrics-${VERSION}-${TIMESTAMP}.json"
PROMETHEUS="http://127.0.0.1:9090"

echo "{" > "$OUTPUT_FILE"
FIRST=true

query_and_append() {
  local METRIC=$1
  local LABEL=$2
  local RESULT=$(curl -sf "$PROMETHEUS/api/v1/query" \
    --data-urlencode "query=$METRIC" 2>/dev/null | python3 -c "
import json,sys
try:
  d = json.load(sys.stdin)
  if d['status'] == 'success' and d['data']['result']:
    print(d['data']['result'][0]['value'][1])
  else:
    print('N/A')
except:
  print('N/A')
" 2>/dev/null || echo "N/A")

  if [ "$FIRST" = true ]; then
    FIRST=false
  else
    echo "," >> "$OUTPUT_FILE"
  fi
  echo "  \"$LABEL\": \"$RESULT\"" >> "$OUTPUT_FILE"
}

# HTTP 请求指标 (Spring Boot 3.x Micrometer)；sum 汇总所有端点（否则 result[0] 常命中 /actuator）
query_and_append "sum(rate(http_server_requests_seconds_count{application='program-service'}[${RATE_WINDOW}]))" "program_service_qps"
# v4 走 mq 异步路径，order-service 无 http 流量，qps 恒为 0，对比不公平 → 标注 N/A 不参与对比
if [[ "$VERSION" == v4-* ]]; then
  if [ "$FIRST" = true ]; then
    FIRST=false
  else
    echo "," >> "$OUTPUT_FILE"
  fi
  echo "  \"order_service_qps\": \"N/A (mq 异步路径)\"" >> "$OUTPUT_FILE"
else
  query_and_append "sum(rate(http_server_requests_seconds_count{application='order-service'}[${RATE_WINDOW}]))" "order_service_qps"
fi

# JVM 指标
query_and_append "rate(jvm_gc_pause_seconds_sum[${RATE_WINDOW}])" "gc_pause_seconds_total"
query_and_append "jvm_memory_used_bytes{area='heap'}" "heap_used_bytes"
query_and_append "jvm_threads_live_threads" "live_threads"

# HikariCP 连接池
query_and_append "hikaricp_connections_active{pool='HikariPool-1'}" "hikari_active_connections"
query_and_append "hikaricp_connections_pending{pool='HikariPool-1'}" "hikari_pending_threads"

# Kafka consumer lag（create_order_data 消费组；v4 异步链路是否积压/消费成瓶颈）
KAFKA_LAG=$(docker exec ticketflow-kafka /opt/bitnami/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group create_order_data 2>/dev/null \
  | awk 'NR>1 && NF>=6 {lag+=$6} END {print lag+0}')
if [ -z "$KAFKA_LAG" ]; then
  KAFKA_LAG="N/A"
fi
if [ "$FIRST" = true ]; then
  FIRST=false
else
  echo "," >> "$OUTPUT_FILE"
fi
echo "  \"kafka_consumer_lag\": \"$KAFKA_LAG\"" >> "$OUTPUT_FILE"

echo "" >> "$OUTPUT_FILE"
echo "}" >> "$OUTPUT_FILE"

# 格式化一下
python3 -c "
import json
with open('$OUTPUT_FILE') as f:
  d = json.load(f)
with open('$OUTPUT_FILE', 'w') as f:
  json.dump(d, f, indent=2)
" 2>/dev/null

echo "指标已保存到: $OUTPUT_FILE"
echo "=== Version: $VERSION ==="
python3 -c "
import json
with open('$OUTPUT_FILE') as f:
  d = json.load(f)
for k, v in d.items():
  print(f'  {k}: {v}')
"
