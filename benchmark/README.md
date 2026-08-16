# Benchmark（压测）

对下单链路 V1~V4 四个演进版本做性能压测：定位单机吞吐上限与拐点、归属瓶颈、量化版本差异。
Gatling 开环到达率模型灌压，Prometheus 采集资源指标，落库对账验证正确性。
结果摘要见根目录 README「性能基准」章节，本目录是完整方法与数据。

## 工具与环境

| 项 | 说明 |
|----|------|
| 压测工具 | Gatling 3.11.5（Scala 2.13.14，gatling-maven-plugin 4.9.5），Maven 运行（`pom.xml`） |
| 被测服务 | `program-service`，直连 `127.0.0.1:6086`（不走网关） |
| 中间件 | Docker：MySQL / Redis / Kafka / Nacos |
| 指标源 | Prometheus `127.0.0.1:9090` |
| 指标采集 | `scripts/collect-metrics.sh`：服务 QPS、GC 停顿、堆、线程、Hikari 连接池、Kafka consumer lag |
| 结果聚合 | Python3 `scripts/build-result.py`：成功率、p50/p95、错误码分桶、落库对账 |
| 拓扑 | 压测机 = 被测机（同机压测） |

## 方法

### 压测模型

- **闭环（closed）**：`rampUsers(concurrency)` 启动 N 个用户，每用户 `during(duration)` 循环下单。
  早期用于正确性验证（落库差、防超卖、v4 异步无损），已验证并继承。
- **开环（openloop）**：`constantUsersPerSec(rate).during(duration)`，恒定到达率灌入，
  每用户只下单一次。用于找吞吐上限与拐点。

闭环的吞吐被"等响应"锁死（Little's Law：吞吐 = 并发 ÷ 响应时间），造不出
"到达速率 > 消费能力"的差额，因此 v4 的 Kafka 异步削峰优势在闭环下不可观测 —— 性能对比必须用开环。

### 场景与数据

- 下单场景：`POST /program/order/create/{v1|v2|v3|v4}`，ticketCount=1，选座
- 数据（`data/test-data.sql` 幂等造数，`data/prepare-local.sh` 导入）：
  program_id=9999（允许选座），7 个票档（901-905 各 2 万、906/907 各 1 万），
  12 万座位，4998 个用户（每用户自己即购票人）
- 座位分配：全局 AtomicInteger 游标按票档分组遍历，跨用户不重复取座，
  避免回访已锁座位产生 SEAT_OCCUPY 污染吞吐统计
- 每档之间必须 reset 数据 + 重新导出座位 CSV（`run-single.sh` 内部完成）+ 预热
  （`program/data/preheat`，入参为 `programId`）

### 每轮流程（`run-single.sh`，7 步）

1. `permit_choose_seat=1` + 数据预热（DB reset + 座位/余票入缓存）
2. 座位导出与校验（12 万行、分档数量校验，缺档报错）
3. 压测前 `d_order` 落库计数
4. Gatling 压测（`mvn gatling:test`）
5. Prometheus 指标采集（压测结束立即采）
6. 等 mq 消费 30s 后落库计数
7. `build-result.py` 生成结果 json（成功率、p50/p95、错误码分桶、落库差）

### 口径

- **成功率** = HTTP 200 且业务 code=0 的请求占比（Gatling ok/total）
- **落库差** = 压测前后 `d_order` 增量 vs 成功数（对账应闭合；v4 需等 mq 消费完再计数）
- **错误码**（`DefaultExceptionHandler`）：

| 码 | 含义 |
|----|------|
| 0 | 成功 |
| 70005 | 分布式锁加锁失败（tryLock 3s 超时，v2/v3/v4） |
| 40001/40002/40003 | 选座/余票竞争业务失败 |
| -100 | 未捕获系统异常（`ApiResponse.error()` 兜底） |

### 运行方式

```bash
# 单版本单档：开环 120 QPS × 60s
bash scripts/run-single.sh v4 0 60 openloop 120

# 阶梯拐点扫描：开环多档位依次跑
bash scripts/run-staircase.sh v4 "40 80 120 160 200" 60

# 四版本对比（闭环 30/60/120 并发 × 20s）
bash scripts/run-compare.sh

# 聚合对比报告（读取 results/ 下结果生成 markdown 表）
bash scripts/compare-report.sh
```

产物写入 `results/`（gitignore，不入库）：`result-{version}-r{rate}*.json`、
`metrics-{version}-r{rate}*.json`、`failure-*.json`、`compare-report-*.md`。

## 结果

下述数据均为本地单机（压测机=被测机）60s 开环窗口实测。

### 1. V4 拐点定位

**修复前**（锁内全量读座位 hash + 锁内同步发 Kafka）：

| rate | 成功率 | p50 | 70005 | 瓶颈 |
|------|--------|-----|-------|------|
| 40 | 93.7% | 1686ms | 151 | 锁内全量读 2 万座位 + 锁内 Kafka await |
| 80 | 46.2% | 6505ms | 2585 | 同上 |
| 120 | 25.5% | 16887ms | 3396 | 同上 |
| 160 | 18.2% | 17162ms | 3507 | 同上 |
| 200 | 13.6% | 17074ms | 3787 | 同上 |

根因（jstack 实证）：锁内每次请求全量读取+反序列化整个票档座位 hash（2 万 field），
Redis 大读取拉长锁持有时间 → `tryLock(3s)` 超时 → 70005。

修复内容：锁内只做 Lua 扣减、Kafka 发送移出锁；座位缓存已预热时跳过锁内全量读；
`data/preheat` 入参由 `id` 修正为 `programId`（原参数错误导致预热从未生效，
缓存与 DB 不一致引发 40001）。

**修复后**（同一开环协议）：

| rate | 成功率 | p50 | 70005 | 瓶颈 |
|------|--------|-----|-------|------|
| 40 | 100% | 19ms | 0 | 无 |
| 80 | 100% | 101ms | 0 | 无 |
| 120 | 99.9% | 1956ms | 0 | mq 消费开始积压 |
| 160 | 62.9% | 11615ms | 517 | Tomcat 线程池 + mq 消费 |
| 200 | 57.2% | 12326ms | 150 | Tomcat 线程池 + mq 消费 |

结论：拐点从 40-80 QPS 上移至 **120-160 QPS**（提升 2-3 倍）。

### 2. 四版本同协议对比（80/120/160/200 × 60s 开环）

| version | 80 QPS | 120 QPS | 160 QPS | 200 QPS |
|---------|--------|---------|---------|---------|
| v1（整节目粗锁 + 同步建单） | 96.7% | 12.1% | 17.7% | 11.9% |
| v2（票档级双锁 + 同步建单） | 85.3% | 34.5% | 64.7% | 13.3% |
| v3（本地锁模板 + 同步建单） | 98.8% | 36.1% | 37.1% | 38.2% |
| v4（本地锁 + Lua 原子扣减 + Kafka 异步建单） | 100% | 99.9% | 81.0% | 66.5% |

结论：
- v4 异步化优势在高并发下兑现——**仅 v4 扛住 120 QPS**（99.9%），
  v1/v2/v3 同步路径 120 QPS 全部崩（12-36%）
- 版本排序：**v4 > v3 > v2 ≈ v1**
- v4 的 160/200 两档为 mq 消费端扩容后的重测数据（见下节）

### 3. mq 消费端扩容

- 根因：`create_order` topic 仅 3 分区（消费并行度上限），高到达率下消费积压，
  尾部消息延迟超过 `MESSAGE_DELAY_TIME`（60s）被丢弃（DISCARD_ORDER 累计 3665 条，对账任务可补偿）
- 修复：topic 分区 3→12 + `@KafkaListener concurrency` 3→12
- 效果：120 QPS 落库差 5953→1411（消费吞吐约 ×4）；
  160 档成功率 62.9%→81.0%、200 档 57.2%→66.5%；70005 几乎归零

### 4. V4 校验链单机优化（profiling 驱动）

- profiling（120 QPS + jstack 采样）：主瓶颈为本地锁竞争——476 个 http-nio 线程阻塞在
  `localLockExecute` tryLock，仅 7 个锁内执行；
  根因是单机 Redis 命令总量饱和（每单约 20-30 条命令：2×detailV2 + 锁内读锁 +
  防重 + 缓存查询），120 QPS ≈ 每秒 2400-3600 条命令，接近单机 Redis 单线程上限
  → 命令排队 → 锁内变慢 → 锁持有拉长 → 竞争加剧（恶性循环）
- 优化：
  1. 校验链 `detailV2` 去重（每单省约 10 条 Redis 命令）
  2. `ProgramDetailCheckHandler` 改用轻量二级缓存（只需 2 个字段）
  3. 锁内余票缓存已预热时跳过带分布式读锁的调用（返回值未使用）
- 效果：120 QPS 下 70005 锁失败归零、100% 成功

### 瓶颈总结（单机环境）

1. 单机 Redis 单线程命令总量最先饱和（120 QPS 已接近上限）
2. Tomcat 线程池（160+，同步请求排队）
3. Kafka 消费端（120+ 积压，经扩容缓解）

### 测量局限

- 压测机 = 被测机，CPU 竞争抬高低并发延迟，p50/p95 绝对值有噪声
  （60s vs 120s 窗口差异显著），精确 A/B 需分离压测机
- 单机 Redis 为物理瓶颈；结果随硬件与部署环境变化

## 目录结构

```
benchmark/
├── pom.xml                # Gatling 3.11.5 + scala-maven-plugin
├── data/                  # 造数：test-data.sql / test-data.csv / cleanup-data.sql / prepare-local.sh
├── scripts/               # 运行与聚合：run-single / run-staircase / run-compare / compare-report / collect-metrics / lib-orders / build-result.py
├── src/test/scala/
│   ├── simulations/       # OrderBenchmark（下单）/ ReadBenchmark（读侧）/ VersionCompareSimulation
│   ├── scenarios/         # CreateOrderScenario / OpenLoopOrderScenario / ProgramDetailScenario / VersionCompareScenario / OrderResultCounter
│   ├── feeders/           # TestDataFeeder / SeatPool / OpenLoopSeatPool
│   └── config/            # TicketFlowProtocol（HTTP 协议与 baseUrl）
└── results/               # 结果产物（gitignore，不入库，保留全部原始 json/log）
```

## V5 版本支持

`OrderBenchmark` 通过 `-DappVersion=v5` 指定版本（`/program/order/create/v5`）；`VersionCompareScenario` 已纳入 `v5` 端点参与四版本对比。

### V5 单机冒烟对比（2026-08-11，见 V5-Architecture-Design.md 附二）

50 并发 × P40 开环（不选座自动匹配）：
- V4：50/50 成功，无失败类
- V5 初版（整体无锁）：3/50，21×40001（自动匹配互相抢占相邻座位）
- V5 修复版（仅自动匹配路径加窄粒度锁，选座无锁）：34/50，40001 消除

结论与量化指标（QPS/RT/落库闭合率）需在稳定环境下用 `run-single.sh v5 ...` 开环跑分补充；当前环境存在 `DelayConsumerQueue`（Redisson 延迟队列）退化导致预热间歇失败的已知问题（详见设计文档附二第 5 节）。