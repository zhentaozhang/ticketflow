#!/usr/bin/env bash
# =============================================================================
# 镜像漏洞扫描（Trivy）+ 可选 SBOM（CycloneDX）。
#
# 作为 CD「制品打包 → 发布」之间的安全卡口：命中 HIGH/CRITICAL 即失败，阻断推送。
#
# 环境变量：
#   IMAGE_TAG   默认 0.0.1-SNAPSHOT
#   SEVERITY    默认 "HIGH,CRITICAL"
#   SBOM_DIR    设置后，为每个镜像产出 CycloneDX SBOM 到该目录（CI 可上传为 artifact）
#   SERVICES    默认全部 9 个服务（可用空格分隔覆盖）
#
# 说明：本机未安装 trivy 时优雅跳过（CI 会先安装 trivy）。
# =============================================================================
set -uo pipefail
cd "$(dirname "$0")/.."

IMAGE_TAG="${IMAGE_TAG:-0.0.1-SNAPSHOT}"
SEVERITY="${SEVERITY:-HIGH,CRITICAL}"
SERVICES="${SERVICES:-gateway user base-data customize program order pay admin migrate}"
SBOM_DIR="${SBOM_DIR:-}"

if ! command -v trivy >/dev/null 2>&1; then
  echo "trivy 未安装，跳过镜像扫描（本机安装：brew install trivy；CI 用安装脚本）"
  exit 0
fi

mkdir -p "$SBOM_DIR" 2>/dev/null || true
fail=0

for svc in $SERVICES; do
  img="ticketflow/${svc}:${IMAGE_TAG}"
  echo ">> scan ${img} (severity=${SEVERITY})"
  if ! trivy image --quiet --severity "$SEVERITY" --exit-code 1 "$img"; then
    echo "  [fail] ${img} 命中 ${SEVERITY} 漏洞"
    fail=1
  fi
  if [ -n "$SBOM_DIR" ]; then
    trivy image --quiet --format cyclonedx --output "${SBOM_DIR}/${svc}.cdx.json" "$img" \
      && echo "  [sbom] ${SBOM_DIR}/${svc}.cdx.json"
  fi
done

if [ "$fail" -ne 0 ]; then
  echo "== 镜像扫描失败 =="
  exit 1
fi
echo "== 镜像扫描通过 =="
