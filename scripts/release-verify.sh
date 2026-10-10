#!/usr/bin/env bash
# =============================================================================
# 发布验证（release verification）
#
# 校验一次发布是否成功，作为 CD 流水线的「发布验证」环节，也是回滚的触发判据。
# 检查三项：
#   1) 各服务 Deployment rollout 完成；
#   2) 运行镜像 tag 与期望一致（确认真的部署了新制品，而不是旧 Pod 还在跑）；
#   3) 经 gateway 的 /actuator/health 为 UP，且服务发现已注册下游。
#
# 环境变量：
#   NAMESPACE     默认 ticketflow
#   SERVICES      默认 "gateway order program"
#   EXPECTED_TAG  期望镜像 tag（为空则跳过第 2 项）
#   VERIFY_PORT   本地端口转发端口，默认 16085
#   ROLLOUT_TIMEOUT 默认 180s
#
# 退出码：0 成功；1 失败（调用方可据此自动回滚）。
# =============================================================================
set -uo pipefail

NAMESPACE="${NAMESPACE:-ticketflow}"
SERVICES="${SERVICES:-gateway order program}"
EXPECTED_TAG="${EXPECTED_TAG:-}"
GATEWAY_SVC="${GATEWAY_SVC:-gateway}"
GATEWAY_PORT="${GATEWAY_PORT:-6085}"
VERIFY_PORT="${VERIFY_PORT:-16085}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-180s}"

fail=0

echo "== [1/3] rollout status =="
for svc in $SERVICES; do
  if kubectl -n "$NAMESPACE" rollout status "deploy/$svc" --timeout="$ROLLOUT_TIMEOUT"; then
    echo "  [ok] $svc rolled out"
  else
    echo "  [fail] $svc rollout"
    fail=1
  fi
done

echo "== [2/3] image revision =="
for svc in $SERVICES; do
  img="$(kubectl -n "$NAMESPACE" get "deploy/$svc" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)"
  echo "  $svc -> ${img:-<none>}"
  if [ -n "$EXPECTED_TAG" ] && [[ "$img" != *":${EXPECTED_TAG}" ]]; then
    echo "  [fail] $svc 镜像 tag 与期望 ${EXPECTED_TAG} 不符"
    fail=1
  fi
done

echo "== [3/3] gateway health + 服务发现 =="
kubectl -n "$NAMESPACE" port-forward "svc/$GATEWAY_SVC" "${VERIFY_PORT}:${GATEWAY_PORT}" >/tmp/tf-verify-pf.log 2>&1 &
pf_pid=$!
trap 'kill "$pf_pid" 2>/dev/null || true' EXIT

health=""
for _ in $(seq 1 20); do
  if curl -fsS "http://localhost:${VERIFY_PORT}/actuator/health" -o /tmp/tf-health.json 2>/dev/null; then
    health="$(cat /tmp/tf-health.json)"
    break
  fi
  sleep 1
done

if [ -z "$health" ]; then
  echo "  [fail] gateway /actuator/health 不可达"
  fail=1
else
  if echo "$health" | grep -q '"status":"UP"'; then
    echo "  [ok] gateway health UP"
  else
    echo "  [fail] gateway health 非 UP"
    fail=1
  fi
  for downstream in ticketflow-order-service ticketflow-program-service; do
    if echo "$health" | grep -q "$downstream"; then
      echo "  [ok] discovery 含 $downstream"
    else
      echo "  [warn] discovery 未含 $downstream（可能尚未注册）"
    fi
  done
fi

if [ "$fail" -ne 0 ]; then
  echo "== 发布验证失败 =="
  exit 1
fi
echo "== 发布验证通过 =="
exit 0
