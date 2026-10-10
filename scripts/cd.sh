#!/usr/bin/env bash
# =============================================================================
# 本地 CD 编排：构建 → 载入集群 → 滚动更新 → 发布验证（失败自动回滚）。
#
# 用途：在没有远程 runner 的情况下，用 kind 集群完整跑通「一次交付」，
#       与 .github/workflows/cd.yml 的 deploy 阶段同构（CI 里换成 kubectl set image 到远端集群）。
#
# 环境变量：
#   IMAGE_TAG      镜像 tag，默认当前 commit（短 SHA）
#   NAMESPACE      默认 ticketflow
#   SERVICES       默认 "gateway order program"
#   SKIP_BUILD=1   跳过镜像构建（复用已有镜像，便于快速验证 CD 编排本身）
#   KIND_CLUSTER   默认 ticketflow
#
# 用法：
#   bash scripts/cd.sh
#   SKIP_BUILD=1 IMAGE_TAG=cdtest bash scripts/cd.sh
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

NAMESPACE="${NAMESPACE:-ticketflow}"
SERVICES="${SERVICES:-gateway order program}"
IMAGE_TAG="${IMAGE_TAG:-$(git rev-parse --short HEAD)}"
SKIP_BUILD="${SKIP_BUILD:-0}"
KIND_CLUSTER="${KIND_CLUSTER:-ticketflow}"
LOCAL_TAG="0.0.1-SNAPSHOT"

echo ">> CD: tag=${IMAGE_TAG} namespace=${NAMESPACE} services='${SERVICES}'"

if [ "$SKIP_BUILD" != "1" ]; then
  echo ">> [1/4] 构建镜像（docker/build.sh images）"
  bash docker/build.sh images
else
  echo ">> [1/4] 跳过构建（SKIP_BUILD=1）"
fi

echo ">> [2/4] 打标并载入 kind（若集群存在）"
if command -v kind >/dev/null 2>&1 && kind get clusters 2>/dev/null | grep -qx "$KIND_CLUSTER"; then
  images=()
  for svc in $SERVICES; do
    docker tag "ticketflow/${svc}:${LOCAL_TAG}" "ticketflow/${svc}:${IMAGE_TAG}"
    images+=("ticketflow/${svc}:${IMAGE_TAG}")
  done
  kind load docker-image "${images[@]}" --name "$KIND_CLUSTER"
else
  echo "   （未检测到 kind 集群 ${KIND_CLUSTER}，跳过加载；部署远端集群请先推送镜像）"
fi

echo ">> [3/4] 滚动更新到 tag=${IMAGE_TAG}"
for svc in $SERVICES; do
  kubectl -n "$NAMESPACE" set image "deploy/${svc}" "${svc}=ticketflow/${svc}:${IMAGE_TAG}"
done

echo ">> [4/4] 发布验证"
if EXPECTED_TAG="$IMAGE_TAG" NAMESPACE="$NAMESPACE" SERVICES="$SERVICES" bash scripts/release-verify.sh; then
  echo ">> CD 成功：tag=${IMAGE_TAG}"
else
  echo ">> 发布验证失败，自动回滚"
  NAMESPACE="$NAMESPACE" SERVICES="$SERVICES" bash scripts/rollback.sh || true
  echo ">> 已回滚，请排查"
  exit 1
fi
