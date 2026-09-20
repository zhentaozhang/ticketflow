#!/bin/bash
# =============================================================================
# TicketFlow 一键构建 + 启动脚本（本地 Docker 容器化）
#
# 用法：
#   ./docker/build.sh            # 默认：打包 jar -> 构建镜像 -> 后台启动整套系统
#   ./docker/build.sh images     # 只打包 + 构建镜像，不启动容器
#   ./docker/build.sh down       # 停止容器（数据卷保留）
#   ./docker/build.sh ps         # 查看容器状态
#   ./docker/build.sh logs <svc> # 查看某服务日志（如 program）
#
# 环境要求：Docker Desktop / docker compose、Java 17+、Maven 3.8+
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."          # 仓库根目录
COMPOSE="docker compose -f docker/docker-compose.yml"

SVC_LIST=(gateway user base-data customize program order pay admin migrate)
SVC_MODULES="ticketflow-server/ticketflow-gateway-service,ticketflow-server/ticketflow-user-service,ticketflow-server/ticketflow-base-data-service,ticketflow-server/ticketflow-customize-service,ticketflow-server/ticketflow-program-service,ticketflow-server/ticketflow-order-service,ticketflow-server/ticketflow-pay-service,ticketflow-server/ticketflow-admin-service,ticketflow-server/ticketflow-migrate-service"

# 支付宝本地占位密钥：不存在则由示例生成（docker/app/pay.env 已 gitignore，不入库）
ensure_pay_env() {
  if [ ! -f docker/app/pay.env ] && [ -f docker/app/pay.env.example ]; then
    cp docker/app/pay.env.example docker/app/pay.env
    echo "  [ok] 已从 pay.env.example 生成 docker/app/pay.env（本地占位密钥）"
  fi
}

# 宿主机 Maven 打包 + 暂存 fat-jar（并适配容器网络内的 MySQL 地址）
package_and_stage() {
  echo ">> [1/3] Maven 打包（-DskipTests；程序/订单等使用 -exec 分类器 fat-jar）..."
  mvn -B -q -DskipTests -Dmaven.test.skip=true \
      -pl "${SVC_MODULES}" -am package -T 4

  echo ">> [2/3] 暂存并适配 fat-jar（127.0.0.1:3306 -> mysql:3306，供容器内连接 MySQL）..."
  for svc in "${SVC_LIST[@]}"; do
    dir="ticketflow-server/ticketflow-${svc}-service/target"
    if [ -f "${dir}/ticketflow-${svc}-service-0.0.1-SNAPSHOT-exec.jar" ]; then
      jar="${dir}/ticketflow-${svc}-service-0.0.1-SNAPSHOT-exec.jar"
    elif [ -f "${dir}/ticketflow-${svc}-service-0.0.1-SNAPSHOT.jar" ]; then
      jar="${dir}/ticketflow-${svc}-service-0.0.1-SNAPSHOT.jar"
    else
      echo "  !! 缺少 ${svc} fat-jar（${dir}），请检查 Maven 打包" >&2
      exit 1
    fi
    staged="docker/app/jars/${svc}/app.jar"
    mkdir -p "$(dirname "${staged}")"
    if command -v python3 >/dev/null 2>&1; then
      python3 docker/app/patch-jar.py "${jar}" "${staged}"
    else
      echo "  !! 未找到 python3，直接复制未适配 jar" >&2
      cp "${jar}" "${staged}"
    fi
    echo "  [ok] ${svc} ($(du -h "${staged}" | cut -f1))"
  done
}

case "${1:-up}" in
  down)   $COMPOSE down; exit 0 ;;
  ps)     $COMPOSE ps;   exit 0 ;;
  logs)   shift; $COMPOSE logs -f --tail=200 "$@"; exit 0 ;;
  images)
    ensure_pay_env
    package_and_stage
    echo ">> [3/3] docker compose build（仅构建镜像）..."
    $COMPOSE build
    echo ">> 镜像构建完成（未启动容器）。"
    exit 0
    ;;
  up) ;;
  *) echo "用法: $0 [up|images|down|ps|logs <svc>]" >&2; exit 2 ;;
esac

ensure_pay_env
package_and_stage

echo ">> [3/3] docker compose build && up -d ..."
$COMPOSE build
$COMPOSE up -d

echo ""
echo ">> 完成。"
echo "   状态查看 : ./docker/build.sh ps"
echo "   日志查看 : ./docker/build.sh logs program"
echo "   Nacos    : http://localhost:8848/nacos"
echo "   网关     : http://localhost:6085（doc.html 聚合文档）"
