# 订单分库分表扩容操作手册（2库4表 → 2库8表）

> 适用范围：ticketflow 订单域（`d_order` / `d_order_ticket_user` / `d_order_ticket_user_record`）
> 方案：基因法（order_number 低位 6 bit 内嵌 user 基因），位运算见
> `ticketflow-spring-cloud-framework/ticketflow-service-common/src/test/java/com/ticketflow/shardingsphere/`
> 下的算法测试。

## 分片规则

- `tableIndex = (tableId - 1) & (orderNumber >> key) & (tableCount - 1)`
- `databaseIndex = (databaseId - 1) & (orderNumber >> (key + log2(tableCount))) & (databaseCount - 1)`
- order_number 生成时低位内嵌用户基因；扩容 4 表 → 8 表后，同一订单的库位与表位不变
  （已验证：现库 7 行订单按 8 表规则位置全部不变，无需数据迁移）。

## 前置检查

```bash
# 1. 8 张分表已存在（每库 d_order_0..7 等）
docker exec ticketflow-mysql mysql -uroot -proot -N -e \
  "SHOW TABLES FROM ticketflow_order_0 LIKE 'd_order_%';"
```

## 迁移命令（migrate-service :6088）

```bash
# 预演（不写数据），期望 totalSkipped = 全部行数、totalMigrated = 0
curl -X POST 'http://127.0.0.1:6088/order/data/sharding/migrate?dryRun=true'

# 正式执行（仅当预演显示有行需要迁移时）
curl -X POST 'http://127.0.0.1:6088/order/data/sharding/migrate?dryRun=false'
```

返回体：`totalScanned` 扫描行数、`totalMigrated` 迁移行数、`totalSkipped` 无需迁移行数。

## 验证命令

```bash
# 1. 服务健康
curl -s http://127.0.0.1:8081/actuator/health

# 2. 按用户查订单（走 8 表路由）
curl -X POST 'http://127.0.0.1:8081/order/select/list' \
  -H 'Content-Type: application/json' \
  -d '{"pageNumber":1,"pageSize":10,"userId":<userId>}'

# 3. 现库位运算审计（bad=0 为通过），检查脚本见本次改动提交说明
```

## 回滚预案

1. 改回 `ticketflow-server/ticketflow-order-service/src/main/resources/shardingsphere-order-local.yaml`：
   `actualDataNodes` 0..7 → 0..3，`table-sharding-count` / `sharding-count` 8 → 4
2. 重新打包并重启 order-service：
   `mvn -pl ticketflow-server/ticketflow-order-service package -DskipTests`
3. 数据无需回滚：4 表与 8 表规则下数据落位一致（位运算不变量），
   回滚只影响新写入的订单分表范围。

## 注意事项

- 迁移前先备份：`mysqldump` 6 个业务库（见备份目录约定）。
- 扩容前确认种子脚本含 `_4..7` 建表段（`sql/cloud/ticketflow_order_{0,1}.sql`）。
- `d_sharding_route_mapping` 为旧方案遗留死表，已从现库与种子中移除，勿再创建。
