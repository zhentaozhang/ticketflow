#!/bin/bash
# =============================================================================
# TicketFlow MySQL 首次启动初始化脚本（仅当数据卷为空时由 mysql 官方镜像执行）
#
# 按顺序执行 sql/cloud 下的建库与建表/种子脚本：
#   1) 1_ticketflow_cloud_create_database.sql   建 10 个业务库
#   2) ticketflow_*.sql                         各库建表 + 种子数据
#   （2_ticketflow_pay_alter.sql 是旧库增量脚本，新部署无需执行，跳过）
# =============================================================================
set -e

MYSQL=(mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" --default-character-set=utf8mb4)

echo "[mysql-init] create databases ..."
"${MYSQL[@]}" < /sql/1_ticketflow_cloud_create_database.sql

echo "[mysql-init] create tables & seed data ..."
for f in /sql/ticketflow_*.sql; do
  echo "[mysql-init] executing $(basename "$f")"
  "${MYSQL[@]}" < "$f"
done

echo "[mysql-init] done."
