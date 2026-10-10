#!/bin/bash
# =============================================
# 落库对账辅助函数：d_order 分片 ds_0/1 × d_order_0..7 = 16 张表
# 每单 1 条 d_order 记录 → 求和即订单总量
#
# 两种模式（由 TARGET_HOST 环境变量切换）：
#   默认单机：MySQL 跑在 docker 容器内（容器名 ticketflow-mysql），
#             宿主机 mysql 9.x 不支持 mysql_native_password → 必须 docker exec
#   局域网双机：TARGET_HOST=<被测机IP> 时用压测机本地 mysql 客户端远程连接（需 brew install mysql-client）
# =============================================

if [ -n "$TARGET_HOST" ]; then
  # 双机模式：压测机本地 mysql 客户端 → 被测机 3306（防火墙需放行 3306）
  MYSQL() {
    mysql -h"$TARGET_HOST" -P3306 -uroot -proot -N "$@"
  }
else
  # 单机模式：docker exec 进入被测机上的 mysql 容器
  MYSQL() {
    docker exec ticketflow-mysql mysql -uroot -proot -N "$@"
  }
fi

count_orders() {
  local QUERY="SELECT SUM(c) FROM ("
  local parts=()
  for db in 0 1; do
    for t in 0 1 2 3 4 5 6 7; do
      parts+=("SELECT COUNT(*) AS c FROM ticketflow_order_${db}.d_order_${t}")
    done
  done
  # 注意：IFS 只取首个字符做分隔（' UNION ALL ' 会退化成空格）→ 用循环显式拼接
  local JOINED="${parts[0]}"
  for p in "${parts[@]:1}"; do
    JOINED="${JOINED} UNION ALL ${p}"
  done
  QUERY="${QUERY}${JOINED}) t;"
  MYSQL -e "$QUERY"
}
