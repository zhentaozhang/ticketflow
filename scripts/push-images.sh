#!/usr/bin/env bash
# =============================================================================
# 将本地构建的 ticketflow/<svc> 镜像按 commit SHA 打标签并推送到镜像仓库。
#
# 用法：
#   REGISTRY=ghcr.io/<org> ./scripts/push-images.sh
#   REGISTRY=ghcr.io/<org> GIT_SHA=abc1234 ./scripts/push-images.sh
#
# 说明：
#   * 镜像必须已由 docker/build.sh 构建（本地 tag 为 ticketflow/<svc>:0.0.1-SNAPSHOT）。
#   * 使用 commit SHA 作为 tag，保证可追溯、可回滚；不要用 latest/SNAPSHOT 覆盖。
#   * 未登录仓库时先执行 docker login <registry>。
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

REGISTRY="${REGISTRY:?请设置 REGISTRY，例如 ghcr.io/your-org}"
GIT_SHA="${GIT_SHA:-$(git rev-parse --short HEAD)}"
SVC_LIST=(gateway user base-data customize program order pay admin migrate)

for svc in "${SVC_LIST[@]}"; do
  local_img="ticketflow/${svc}:0.0.1-SNAPSHOT"
  remote_img="${REGISTRY}/ticketflow-${svc}:${GIT_SHA}"
  docker tag "${local_img}" "${remote_img}"
  docker push "${remote_img}"
done

echo "已推送 ${#SVC_LIST[@]} 个镜像，tag=${GIT_SHA} -> ${REGISTRY}"
