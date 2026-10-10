#!/usr/bin/env bash
# =============================================================================
# 回滚：将各服务 Deployment 回退到上一个版本（kubectl rollout undo）。
#
# 环境变量：
#   NAMESPACE 默认 ticketflow
#   SERVICES  默认 "gateway order program"
#   ROLLOUT_TIMEOUT 默认 180s
#
# 说明：依赖 Deployment 的 revisionHistoryLimit 保留历史 ReplicaSet；
#       镜像 tag 使用 commit SHA 时，回退到的即上一版真实制品。
# =============================================================================
set -euo pipefail

NAMESPACE="${NAMESPACE:-ticketflow}"
SERVICES="${SERVICES:-gateway order program}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-180s}"

for svc in $SERVICES; do
  echo ">> rollback deploy/$svc"
  kubectl -n "$NAMESPACE" rollout undo "deploy/$svc"
  kubectl -n "$NAMESPACE" rollout status "deploy/$svc" --timeout="$ROLLOUT_TIMEOUT"
done

echo ">> 回滚完成"
echo
cat <<'NOTE'
注意（镜像归属）：
  Deployment 由 kubectl apply -k 初始创建；运行时镜像由 CD 用 'kubectl set image' 部署、
  本脚本用 'kubectl rollout undo' 回滚。rollout undo 不会更新 last-applied-configuration
  注解，因此请勿再对已部署对象执行 kubectl apply -k 覆盖（否则可能与回滚结果冲突）。
  如需声明式管理镜像，请改用 'kustomize edit set image' + apply（或接入 GitOps）。
NOTE
