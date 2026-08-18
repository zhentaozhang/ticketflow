# coupon-seckill — 优惠券秒杀独立单体（验证项目）

> 分支：`feature/coupon-seckill`

在不动现有 ticketflow 微服务代码的前提下，独立验证"优惠券秒杀"完整业务闭环与高并发方案，
后续以独立微服务 `ticketflow-coupon-service` 形式平滑集成进现有 Spring Cloud Alibaba 体系。

## 文档

- [01-技术设计](docs/01-技术设计.md)：业务模型、核心流程、数据库设计、Redis/Kafka 方案、库存与幂等、秒杀链路、异常与一致性、监控、集成方案、里程碑

## 核心方案速览

| 关注点 | 方案 |
|--------|------|
| 抢购峰值 | 同步路径只做 Redis（单 Lua 原子完成 时间窗+限购+扣库存），QPS 可达 10w+ |
| 削峰 | Kafka 异步发券落库，DB 不再是瓶颈 |
| 超卖 | Lua 原子扣减 + DB 唯一索引 + 对账任务 三层防线 |
| 幂等 | requestId（请求层）+ 唯一索引（落库层）+ order_no/coupon_no（发券层） |
| 一致性 | 抢购最终一致（对账收敛）；锁券/核销/退回强一致（DB 事务+乐观锁） |
| 集成 | 独立微服务 + order-service 契约对接（lock/use/return） |

## 目录规划

```
├── docs/           # 设计文档
├── sql/            # 建表脚本
└── src/            # Spring Boot 3.3 单体应用（实现阶段）
```

## 里程碑

M0 设计 → M1 骨架+活动管理 → M2 抢购主链路 → M3 用券闭环+对账 → M4 压测优化 → M5 集成
