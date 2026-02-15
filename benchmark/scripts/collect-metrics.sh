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

# HTTP 请求指标 (Spring Boot 3.x Micrometer)
query_and_append "rate(http_server_requests_seconds_count{application='program-service'}[30s])" "program_service_qps"
query_and_append "rate(http_server_requests_seconds_count{application='order-service'}[30s])" "order_service_qps"

# JVM 指标
query_and_append "rate(jvm_gc_pause_seconds_sum[30s])" "gc_pause_seconds_total"
query_and_append "jvm_memory_used_bytes{area='heap'}" "heap_used_bytes"
query_and_append "jvm_threads_live_threads" "live_threads"

# HikariCP 连接池
query_and_append "hikaricp_connections_active{pool='HikariPool-1'}" "hikari_active_connections"
query_and_append "hikaricp_connections_pending{pool='HikariPool-1'}" "hikari_pending_threads"

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
