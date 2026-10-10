#!/bin/bash
# =============================================================================
# 下载 OpenTelemetry Java Agent
#
# 用法：
#   ./docker/otel/download-agent.sh              # 下载默认稳定版 v2.31.1
#   OTEL_AGENT_VERSION=v2.30.0 ./docker/otel/download-agent.sh  # 指定版本
#
# 产物：docker/otel/opentelemetry-javaagent.jar
# 说明：下载完成后执行 ./docker/build.sh 即可让 8 个应用容器自动挂载 agent，
#       Trace 数据经 OTLP 上报 docker-compose 中的 jaeger（UI: http://localhost:16686）。
#       （也可省略本脚本：将任意版本 opentelemetry-javaagent.jar 放入 docker/otel/ 即可）
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="${SCRIPT_DIR}"
TARGET="${TARGET_DIR}/opentelemetry-javaagent.jar"

# 默认稳定版（2025-11 验证 v2.31.1 为 latest）；可通过环境变量覆盖
OTEL_AGENT_VERSION="${OTEL_AGENT_VERSION:-v2.31.1}"
BASE_URL="https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/${OTEL_AGENT_VERSION}"

mkdir -p "${TARGET_DIR}"

echo ">> 下载 OpenTelemetry Java Agent ${OTEL_AGENT_VERSION} ..."
curl -fsSL -o "${TARGET}" "${BASE_URL}/opentelemetry-javaagent.jar"
# 官方已不再对每个版本发布 .sha256（改为 .asc 签名/attestation），这里尽力下载校验，缺失不阻断
if curl -fsSL -o "${TARGET}.sha256" "${BASE_URL}/opentelemetry-javaagent.jar.sha256"; then
  if command -v shasum >/dev/null 2>&1 || command -v sha256sum >/dev/null 2>&1; then
    HASH_TOOL="shasum -a 256"
    command -v shasum >/dev/null 2>&1 || HASH_TOOL="sha256sum"
    EXPECTED="$(awk '{print $1}' "${TARGET}.sha256" | tr -d '\n')"
    ACTUAL="$(${HASH_TOOL} "${TARGET}" | awk '{print $1}')"
    if [ "${EXPECTED}" = "${ACTUAL}" ]; then
      echo "  [ok] sha256 校验通过"
    else
      echo "  !! sha256 校验失败（expected=${EXPECTED} actual=${ACTUAL}）" >&2
      exit 1
    fi
  else
    echo "  !! 未找到 shasum/sha256sum，跳过校验" >&2
  fi
else
  rm -f "${TARGET}.sha256"
  echo "  [warn] 该版本未发布 .sha256 资产，跳过校验（如需可核对官方 .asc 签名）" >&2
fi

echo ""
echo ">> 完成：$(ls -lh "${TARGET}" | awk '{print $5, $9}')"
echo "   重新构建并启动：./docker/build.sh"
echo "   查看 Trace    ：http://localhost:16686"
