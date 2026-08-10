# 压测计划（Benchmark Plan）

> 本计划是后续压测代码与优化的 **spec**：改造脚本、新增仿真、结果口径均以此为准。
> 状态：v1.0（2026-08-10）

## 1. 目标

用**开环到达率**协议，找出单机（本地本机）环境下下单链路（v1/v2/v3/v4）的**真实吞吐上限与拐点**，
回答原压测回答不了的问题：

1. 四版本的真实吞吐上限（TPS）和拐点（knee point）在哪
2. 单机瓶颈归属：Tomcat 线程池 / DB 连接池 / Redis Lua / Kafka 消费，谁先到顶
3. v4 异步化（Kafka 削峰）的优势在高并发下是否兑现（对比 v1 同步粗锁）
4. 锁策略差异（v1 整节目粗锁 vs v2/v3/v4 细锁）在多票档 vs 单票档下的真实差距

无 SLA 硬性目标（自有项目，纯找上限）。

## 2. 背景：为什么原压测测不出上限

原压测（`simulations/OrderBenchmark`）是**闭环模型**：固定并发 30/60/120，每个虚拟用户
串行"下单→等响应→再下单"（`during(duration)` 循环）。

闭环的致命问题（Little's Law：系统内并发 L = 吞吐 λ × 响应时间 W）：

- 吞吐被"等响应"锁死：吞吐上限 = 并发数 ÷ 响应时间，响应越慢吞吐越低
- 造不出"到达速率 > 消费能力"的差额 → v4 的 Kafka 削峰优势**不可观测**
- 单机 + ≤120 并发未触碰任何资源瓶颈，测的是"锁的语义"（v1 阻塞等锁 vs 其他快速失败），不是性能

**结论：原压测的正确性验证（落库差==成功数、无超卖）有效并继承；性能对比结论作废。**

## 3. 环境与拓扑

- **压测机 = 被测机 = 本机**（jmeter 与 server 同机）。CPU 竞争会抬高低并发延迟，
  影响 p50/p95 绝对值，但**不影响拐点与 TPS 上限的定性判断**（同机对比公平）
- 被测：`program-service` 直连 `127.0.0.1:6086`
- 中间件：Docker（`ticketflow-mysql` / `ticketflow-redis` / `ticketflow-kafka` / `ticketflow-nacos`）
- Prometheus：`127.0.0.1:9090`（`collect-metrics.sh` 依赖）
- 工具：Gatling 3.11.5（`benchmark/pom.xml`，非 jmeter）

## 4. 数据约束（决定加压上限）

`benchmark/data/test-data.sql` 造数（幂等，`prepare-local.sh` 可重复执行）：

| 数据 | 数量 | 说明 |
|------|------|------|
| 节目 | 1（program_id=9999） | 允许选座 |
| 票档 | 7（901-907） | 901-905 各 20000，906-907 各 10000 |
| 库存/座位 | 120000 | 每单消耗 1 张（ticketCount=1，选座） |
| 用户/购票人 | 4998（id 3-5000） | 每用户自己即购票人 |

**库存与到达率的关系**（每单 1 张）：

| 到达率 | 60s 消耗 | 占库存 |
|--------|----------|--------|
| 800 QPS | 48000 | 40% ✅ |
| 1000 QPS | 60000 | 50% ✅ |
| 2000 QPS | 120000 | 100% ⚠️ 极限 |

- **单场景 60s 内，到达率 ≤ 1000 QPS 库存充足**；更高必须缩短时长或加节目
- **每场景（每到达率档位）之间必须 reset 数据 + 重新导出座位 CSV**
  （原 `run-compare.sh` 只在版本间 reset，是缺陷，本轮修复）
- 用户可复用：`@RepeatExecuteLimit` 的 `durationTime=0`（仅防并发，不写幂等标记），
  同用户串行/间歇下单不会被拦
- 座位必须**全局唯一游标**分配（AtomicInteger 递增），避免回访已锁座位产生
  SEAT_OCCUPY（40001/40002/40003）污染吞吐测量

## 5. 指标体系

每场景输出（`build-result.py` 已有大部分，补齐 TPS 与 Kafka lag）：

| 指标 | 来源 | 用途 |
|------|------|------|
| TPS（ok/total） | Gatling stats.json | 吞吐主指标 |
| p50 / p95 / p99 | Gatling stats.json | 延迟分位 |
| 成功率 | stats.ok / stats.total | 整体 |
| 错误码分桶 | simulation.log KO 行 | 锁失败(70005)/系统异常(-100)/占座(4000x) |
| 落库差 | d_order 16 表前后计数 | 对账（v4 需等 mq 消费完） |
| 吞吐-并发拐点曲线 | 阶梯扫描结果聚合 | 找上限 |
| CPU/GC/线程池/连接池 | Prometheus（collect-metrics.sh） | 瓶颈归属 |
| **Kafka consumer lag** | Kafka（新增采集） | v4 消费是否成瓶颈（必补） |

**错误码口径**（`DefaultExceptionHandler` 确认）：

| 码 | 含义 |
|----|------|
| 0 | 成功 |
| 70005 | 分布式锁加锁失败（tryLock 3s 超时，v2/v3/v4） |
| -100 | 未捕获系统异常（`ApiResponse.error()` 兜底；原 v1-120 的 95 个即此类） |
| 40001/40002/40003 | 选座/余票竞争业务失败 |

## 6. 场景矩阵

### Phase 0：基线（继承，不重做）
- 原压测已验证：落库差==成功数（v1/v2/v3/v4 全对账闭合）、无超卖、v4 异步链路无损
- **无需重跑**

### Phase 1：单机拐点定位（先行，决定阶梯上限）
- 先跑 **v4**（链路最复杂：锁 + Lua + Kafka），开环到达率 50→100→200→400→800 各 60s
- 每档 reset 数据
- 产出：TPS-到达率 曲线 + RT-到达率 曲线 + 资源曲线，**确认单机瓶颈归属**
- 目的：确定后续版本对比的到达率档位上限（预计单机 200 线程池在 300-600 QPS 区间先到顶）

**Phase 1 实测结果（2026-08-10）**：

修复前（锁内全量读座位 hash + 锁内同步发 Kafka）：

| rate | 成功率 | p50 | 70005 | 瓶颈 |
|------|--------|-----|-------|------|
| 40 | 93.7% | 1686ms | 151 | 锁内全量读 2 万座位 + 锁内 Kafka await |
| 80 | 46.2% | 6505ms | 2585 | 同上 |
| 120 | 25.5% | 16887ms | 3396 | 同上 |
| 160 | 18.2% | 17162ms | 3507 | 同上 |
| 200 | 13.6% | 17074ms | 3787 | 同上 |

**根因（jstack 实证）**：锁内 `createOrderOperateProgramCacheResolution` 每次请求
`selectSeatResolution` 全量读取+反序列化整个票档座位 hash（2 万 field），锁持有时间被
Redis 大读取拉长 → `tryLock(3s)` 超时 → 70005。

**已实施的修复（对应后续代码 spec 新增 T8/T9）**：

| # | 修复 | 文件 |
|---|------|------|
| T8 | V4 锁内只做 Lua 扣减，Kafka 发送移出锁（新增 `localLockExecute` + `createNewAsyncAfterLock`） | BaseProgramOrder / ProgramOrderService / ProgramOrderV4/V41Strategy |
| T9 | 座位缓存已预热时跳过锁内全量读（`hasSeatResolutionCache` + 预热检查） | ProgramOrderService |
| T10 | 压测预热参数修正：`data/preheat` 入参为 `programId`（原为 `id`，导致预热从未生效、缓存与 DB 不一致引发 40001） | run-single.sh / prepare-local.sh |

修复后（同一开环协议）：

| rate | 成功率 | p50 | 70005 | 超时 | 瓶颈 |
|------|--------|-----|-------|------|------|
| 40 | 100% | 19ms | 0 | 0 | 无 |
| 80 | 100% | 101ms | 0 | 0 | 无 |
| 120 | 99.9% | 1956ms | 0 | 0 | mq 消费开始积压 |
| 160 | 62.9% | 11615ms | 517 | 2972 | Tomcat 线程池 + mq 消费 |
| 200 | 57.2% | 12326ms | 150 | 4951 | Tomcat 线程池 + mq 消费 |

**结论**：拐点从 40-80 上移至 **120-160 QPS**（提升 2-3 倍）；瓶颈从"锁内全量读 +
锁内发 Kafka"转移至"Tomcat 线程池（160+）与 mq 消费端（120+，落库差 < 成功数）。
后续版本对比档位应取 80/120/160/200。

### Phase 2：四版本同协议对比（80/120/160/200 × 60s，开环）

| version | 80 QPS | 120 QPS | 160 QPS | 200 QPS |
|---------|--------|---------|---------|---------|
| v1 | 96.7% (p50 13.8s) | 12.1% | 17.7% | 11.9% |
| v2 | 85.3% (p50 6.1s) | 34.5% | 64.7% | 13.3% |
| v3 | 98.8% (p50 0.9s) | 36.1% | 37.1% | 38.2% |
| v4(修复后) | 100% (p50 0.1s) | 99.9% | 81.0% | 66.5% |

**结论**：v4 异步化优势在高并发下兑现——仅 v4 扛住 120 QPS（99.9%），
v1/v2/v3 同步路径 120 QPS 全部崩（12-36%）。版本排序 v4 > v3 > v2 ≈ v1。

### mq 消费端吞吐优化（对应 spec 新增 T11）

- **根因**：create_order topic 仅 3 分区（消费并行度 = 分区数上限），
  高到达率下消费积压，尾部消息延迟 > MESSAGE_DELAY_TIME(60s) 被丢弃
  （DISCARD_ORDER 累计 3665 条，有对账记录可补偿）
- **修复**：topic 分区 3→12 + `@KafkaListener concurrency 3→12`
  （CreateOrderConsumer）
- **效果**：120 QPS 落库差 5953→1411（消费吞吐 ×4）；
  160 档成功率 62.9%→81.0%、200 档 57.2%→66.5%、70005 几乎归零
- **遗留**：120+ QPS 尾部消息仍可能因 MESSAGE_DELAY_TIME 超时丢弃，
  属业务防超卖设计（延迟取消/座位回滚），由 DISCARD_ORDER + 对账任务补偿

## V5（v2 设计，2026-08-10 实测）

### 设计
V5 = 无锁原子扣减 + fire-and-forget 异步提交 + 批量消费：
- 去掉应用层锁（Lua 原子兜底并发安全）
- Kafka 发送不等待 ack（请求线程不被 Kafka RTT 占用）
- 消费端 batch listener（12 分区）+ 批量 Feign 扣减（一次 RPC 多单）

### 实现（T12-T16）
| # | 改动 | 文件 |
|---|------|------|
| T12 | ProgramOrderV5Strategy（无锁）+ V5 端点 + 版本枚举 | strategy/impl + Controller + ProgramOrderVersion |
| T13 | fire-and-forget：createOrderByMqAsync / createNewAsyncFireAndForget | ProgramOrderService |
| T14 | BusinessThreadPool 扩容（core CPU+1→×2、max ×5→×10、队列 600→2000） | BusinessThreadPool |
| T15 | 批量消费：KafkaConsumerConfig(batch factory) + CreateOrderConsumer 批量 | order-service |
| T16 | 批量 Feign：operateSeatLockAndTicketCategoryRemainNumberBatch + OrderService.createMqBatch | program/order-service |

### 实测（开环 60s）
| rate | v4 | v5第一轮 | v5第二轮 |
|------|-----|---------|---------|
| 80 | 100% | 100% | - |
| 120 | 100% | 96.3% (p50 3374) | 98.2% (p50 1548) |
| 160 | 81.0% | 80.5% | 53.3% |
| 200 | 66.5% | 35.3% | 50.5% |
| 300 | - | 33.9% | 24.0% |

### 结论（未全面达标）
1. **fire-and-forget 生效**：200 档 waiting=0（V4 同档 waiting 数千），请求端吞吐能力提升
2. **120 档改善**：第二轮 p50 3374→1548ms、成功率 96.3%→98.2%
3. **剩余瓶颈**：
   - 单请求处理 ~1.5s（120 QPS 在途 ≈ Tomcat 200 线程满）→ 高到达率 connection timed out
     （需 profiling：Lua 命令密集 or composite 校验链）
   - 消费端落库差仍大（DB 单条事务 + Feign 批量帮助有限）
   - 无锁化在单机 Redis 下并发全开 → Redis 命令堆积
4. **判断**：v5 的异步化方向正确，但单机单 Redis 环境下无法兑现无锁化优势；
   fire-and-forget + 批量消费需配套 Redis 集群 / 消费端 DB 批量 insert 才完整。
   建议保留 v5 方向，单机场景以 V4 为最优。

### Phase 2：四版本同协议对比
- v1/v2/v3/v4 × 多票档均匀分布（模拟真实混票）
- 到达率档位 = Phase 1 探明的拐点前/后各取 1-2 档
- 每版本每档 reset
- 产出：四版本 TPS/延迟/成功率对比表

### Phase 3：锁策略专项
- 单票档（全部请求同一 ticketCategoryId）vs 多票档（均匀 7 档）各跑一轮
- 目的：量化 v1 粗锁 vs v2/v3 细锁的真实差距

### Phase 4：Soak
- 低到达率（≤ 50 QPS）长时间（≥ 5min）跑 v4
- 观察：内存/GC 稳定、Kafka 无积压、落库差闭合

### Phase 5：一致性对账（每轮必做）
- 落库差：压测后 d_order 增量 == 成功数（v4 等 mq 消费完 30s 后计数）
- 防超卖：余票扣减数 == 成功下单数（本轮补充）

## 7. 开环协议定义

- 注入：`constantUsersPerSec(rate).during(60s)`（Gatling 原生开环）
- 请求：`POST /program/order/create/{v1|v2|v3|v4}`，Body 同原闭环（ticketCount=1，单座位选座）
- 座位：全局 AtomicInteger 游标，按票档分组 CSV 顺序遍历（跨用户不重复）
- 用户：4998 池循环（userFeeder 已有），同用户不并发即可
- 每档之间：reset 数据（`/program/reset/execute`）+ 重新导出座位 CSV（`run-single.sh` 已有该逻辑）

## 8. 改造任务清单（后续代码 spec）

| # | 任务 | 文件 | 说明 |
|---|------|------|------|
| T1 | 开环下单场景 | `scenarios/OpenLoopOrderScenario.scala`（新增） | 复用 body 构造 + OrderResultCounter |
| T2 | 开环座位池 | `feeders/SeatPool.scala`（加全局游标）或新增 | AtomicInteger 唯一取座 |
| T3 | 开环仿真入口 | `simulations/OrderBenchmark.scala`（改造） | 支持 `-Dmode=openloop -Drate=N`，constantUsersPerSec |
| T4 | 阶梯运行脚本 | `scripts/run-staircase.sh`（新增） | 每档调用 run-single.sh（内部已含 reset）+ 每档独立落库对账 |
| T5 | 指标补全 | `scripts/collect-metrics.sh` | 新增 Kafka consumer lag 采集 |
| T6 | 结果聚合 | `scripts/build-result.py`、`compare-report.sh` | 支持到达率（rate）标签维度 |
| T7 | 计划外发现 | （视 Phase 1 结果） | v4 锁内发 Kafka 等已知缺陷另行排期 |

## 9. 产物

- `results/result-{version}-r{rate}-*.json`：每档结果
- `results/metrics-{version}-r{rate}-*.json`：资源指标
- `results/staircase-report-*.md`：拐点曲线与对比结论
- 结论：四版本真实上限排序、瓶颈归属、v4 异步优势是否兑现

## 10. 结论标准（无 SLA，定性+定量）

- **拐点**：RT 陡增点 / TPS 不再随到达率增长的到达率值
- **瓶颈归属**：拐点处哪个资源先饱和（线程池 pending / Hikari active+pending / Kafka lag 增长）
- **版本结论**：同到达率下比较 TPS、p95、成功率；v4 的异步优势看"同等到达率下
  请求响应不劣化 + Kafka lag 增长可控 + 落库最终闭合"
