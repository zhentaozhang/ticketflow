# 订单分库分表扩容操作手册（2库4表 → 2库8表）

> 适用范围：ticketflow 订单域（`d_order` / `d_order_ticket_user` / `d_order_ticket_user_record`）
> 方案：基因法（order_number 低位 6 bit 内嵌 user 基因），位运算见
> `ticketflow-spring-cloud-framework/ticketflow-service-common/src/test/java/com/ticketflow/shardingsphere/`
> 下的算法测试。

## 分片规则

- `tableIndex = (tableCount - 1) & orderNumber`（取低 log2(tableCount) 位）
- `databaseIndex = (databaseCount - 1) & (orderNumber >> log2(tableCount))`（跳过表基因位后取低 log2(databaseCount) 位）
- 实现见 `ShardingGeneUtils.tableIndex/databaseIndex`（`ticketflow-spring-cloud-framework/ticketflow-service-common`），
  分片数必须为 2 的幂（`checkPowerOfTwo` 强制校验）。
- order_number 生成时低位内嵌用户基因；扩容 4 表 → 8 表时，**表位是否变化取决于 order_number 的 bit2**：
  仅当 bit2 = 0（即低 3 位 < 4）时表位不变，bit2 = 1 的订单会从 0..3 落到 4..7，需经迁移。
  当前存量数据（现库 7 行订单）低 3 位均 < 4，按 8 表规则位置全部不变，无需迁移；
  但这不是位运算不变量，新订单按 8 表规则可能落入 4..7，回滚前必须迁移回。

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
3. 存量数据无需回滚：当前 7 行订单低 3 位均 < 4，4 表与 8 表规则下落位一致；
   但若 8 表运行期间有新订单落入 4..7，回滚前需先用迁移服务
   （反向配置 8 表 → 4 表）迁回，否则该部分订单路由不到。

## 注意事项

- 迁移前先备份：`mysqldump` 6 个业务库（见备份目录约定）。
- 扩容前确认种子脚本含 `_4..7` 建表段（`sql/cloud/ticketflow_order_{0,1}.sql`）。
- `d_sharding_route_mapping` 为旧方案遗留死表，已从现库与种子中移除，勿再创建。
