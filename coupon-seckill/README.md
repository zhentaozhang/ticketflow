# coupon-seckill — 优惠券秒杀独立单体（验证项目）

> 分支：`feature/coupon-seckill`

在不动现有 ticketflow 微服务代码的前提下，独立验证"优惠券秒杀"完整业务闭环与高并发方案，
后续以独立微服务 `ticketflow-coupon-service` 形式平滑集成进现有 Spring Cloud Alibaba 体系。

## 文档

- [01-技术设计](docs/01-技术设计.md)：业务模型、核心流程、数据库设计、Redis/Kafka 方案、库存与幂等、秒杀链路、异常与一致性、监控、集成方案、里程碑

## 核心方案速览

| 关注点 | 方案 |
|--------|------|
| 抢购峰值 | 同步路径只做 Redis（单 Lua 原子完成 时间窗+限购+扣库存+幂等），QPS 可达 10w+ |
| 削峰 | Kafka 异步发券落库（本地无 Kafka 时可用 mock-messaging=true 内存队列全链路验证） |
| 超卖 | Lua 原子扣减 + DB 唯一索引 + 对账任务 三层防线 |
| 幂等 | requestId（请求层）+ 唯一索引（落库层）+ order_no/coupon_no（发券层） |
| 一致性 | 抢购最终一致（对账收敛）；锁券/核销/退回强一致（DB 事务+乐观锁+Redis SETNX 第一道闸） |
| 分表 | flash_sale_order / user_coupon 按 user_id%2 分片（MyBatis-Plus DynamicTableName 简易实现，集成时切 ShardingSphere） |
| 集成 | 独立微服务 + order-service 契约对接（lock/use/return） |

## 目录结构

```
coupon-seckill/
├── docs/01-技术设计.md     # 设计文档
├── sql/schema.sql          # 建表脚本（含分表）
└── src/
    ├── main/java/com/couponseckill/
    │   ├── common/         # Result / ErrorCode / BizException / 全局异常
    │   ├── config/         # MyBatis-Plus(分表+乐观锁+分页) / Lua 脚本 / RedisKeys / ShardingContext
    │   ├── controller/     # Manage / FlashSale / Coupon(契约) / UserCoupon
    │   ├── service/        # Manage / ActivityCache / FlashSaleGrab / CouponIssue / CouponUse / Query
    │   ├── kafka/          # 消息抽象 + Kafka 实现 + mock 实现 + 消费逻辑
    │   ├── mapper/ entity/ # 与表对应（对账 SQL 跨分片）
    │   ├── task/           # ActivityState / Reconcile / IssueTimeout / CouponExpire / Dlt
    │   └── id/             # 轻量雪花（集成时替换 id-generator-framework）
    └── resources/
        ├── application.yml
        └── lua/            # grab.lua / rollback.lua（外置+预加载）
```

## 实现进度

| 里程碑 | 内容 | 状态 |
|--------|------|------|
| M0 | 技术设计文档 | ✅ |
| M1 | 单体骨架 + 建表 + 活动管理（创建/发布/预热/下架/调库存） | ✅ |
| M2 | 抢购主链路：Lua 原子扣减 + 异步发券 + 结果查询 | ✅ |
| M3 | 用券闭环（锁券/核销/退回）+ 对账/过期/超时/状态推进任务 | ✅ |
| M4 | 压测优化（复用 benchmark/ 落库率口径） | ⏳ 待做 |
| M5 | 集成 ticketflow（独立服务 + order-service 对接 + 灰度） | ⏳ 待做 |

## 运行与验证

```bash
# 前置：本机 MySQL(建 coupon_seckill 库并执行 sql/schema.sql)、Redis
mvn spring-boot:run                 # 默认 mock-messaging=true，无 Kafka 也可跑通全链路
mvn test                            # 集成测试（真实 Redis + MySQL）
```

### 测试覆盖（53/53 通过）

- 纯单元测试 35（Mockito，无外部依赖）：雪花 ID、分表路由/线程隔离、管理校验、Lua 错误码映射、发券幂等三分支、用券全分支
- Lua 脚本测试 7（直连本地 Redis，无 Redis 自动跳过）：原子扣减/负库存回加/限购/幂等/时间窗/回补
- 集成测试 11（真实 Redis + MySQL）：100 并发抢 50 库存零超卖、同 requestId 并发仅一次成功、20 并发锁券单赢家(防双花)、对账修正 Redis 库存

详细过程与踩坑记录见 [docs/02-实现思路与工作留痕.md](docs/02-实现思路与工作留痕.md)

### 接口一览

| 接口 | 说明 |
|------|------|
| POST /manage/template, /manage/activity, /manage/activity/{id}/publish·offline·stock | 管理端 |
| POST /flash-sale/grab (X-User-Id) | 抢购（同步 Redis，异步发券） |
| GET /flash-sale/result?activityId= | 抢购结果轮询 |
| POST /coupon/lock · /use · /return | 用券契约（集成给 order-service） |
| GET /user/coupons?status= | 我的券列表 |
