# ticketflow-id-generator-framework

分布式 ID 生成模块。基于**百度 uid-generator**（CachedUidGenerator + RingBuffer）移植，混入了 Twitter 雪花算法定制链路，用 Redis 取代了原版的 MySQL workerId 分配。

---

## 1. 解决什么问题

业务表（节目、订单、票档、座位…）的主键不能依赖数据库自增：

- **单库自增不够用**：分库分表后每个库的自增会撞号，必须全局唯一
- **主键要带信息**：订单号要能看出"时间 + 实例 + 分片基因"，方便定位问题、按用户聚合
- **性能要够**：抢票场景下主键生成不能成为瓶颈

这个模块提供两条 ID 链路，用途不同：

| 方法 | 用途 | 生成器 |
|---|---|---|
| `getUid()` | 业务主键（program/order/pay/seat…） | CachedUidGenerator（RingBuffer 缓存 + 百度位分配） |
| `getOrderNumber(userId)` | 订单号（下单时生成） | SnowflakeIdGenerator（雪花 + 分片基因法） |

> 为什么是两条而不是一条？→ 订单号要"带用户分片基因"（分库分表路由用），普通主键不需要；基因法会挤占序列号位宽，两套布局分开互不干扰。

---

## 2. 怎么装配的

Spring Boot 3 自动装配，`META-INF/spring/...AutoConfiguration.imports` 注册 3 个配置类：

```
IdGeneratorRedisConfig   (仅当配置了 spring.data.redis.host)
  ├─ idGeneratorRedisTemplate          (String 序列化的 RedisTemplate)
  └─ disposableWorkerIdAssigner        (Redis INCR 分配 workerId，给 getUid 链路)
WorkerNodeConfig
  └─ cachedUidGenerator                (UidGenerator 主 bean：组装 CachedUidGenerator)
IdGeneratorAutoConfig
  └─ workAndDataCenterIdHandler (Lua 原子分配 workId/dataCenterId，给订单号链路)
       └─ workDataCenterId → snowflakeIdGenerator
```

服务里注入 `UidGenerator` 接口即用。Redis 是硬依赖——workerId 必须全局唯一，不能用本机信息哈希（多实例会撞）。

---

## 3. 两条 ID 链路

### 3.1 getUid()：百度 CachedUidGenerator

```
初始化 (afterPropertiesSet)：
  workerId = Redis INCR "uid_work_id"   （22 bits 空间，上限约 419 万次分配）
  RingBuffer 预生成 65536 个 ID 缓存    （bufferSize = 8192 << 3）

运行时：
  getUid() → ringBuffer.take()          （无锁双指针，毫秒级返回）
  后台填充线程 (BufferPaddingExecutor)   （低于 50% 水位自动补货）
```

位布局（1+28+22+13 = 64）：

```
+------+----------------------+----------------+-----------+
| sign |   delta seconds(28)  |  worker id(22) | sequence  |
+------+----------------------+----------------+-----------+
   1          秒级                   Redis分配      13位/秒
```

- **epoch = 2024-05-20**，28 bits 秒 ≈ 8.5 年，**约 2032 年底耗尽**（耗尽前 `getCurrentSecond` 抛异常拒绝生成，不会静默出错）
- 吞吐：单实例每秒 8192 个；RingBuffer 缓存 65536 个，`take` 是纯内存操作
- **预生成的关键**：同一秒内的 ID 只算一次位分配，其余靠 `firstUid + offset` 递增得到（sequence 位在最低位，加法等价序列自增）
- workerId 用 22 bits（而不是经典雪花的 5 bits）是百度原版设计：Redis INCR 全局递增，空间大、永不回绕

### 3.2 getOrderNumber(userId)：雪花 + 基因法

```
workId/dataCenterId = Lua 脚本原子分配（Redis 持久计数器，1024 组合上限）
orderNumber = [时间戳41ms][dc 5][worker 5][序列 6][userId后6位基因 6]
```

基因法核心：

- **生成端**：取 `userId & 0b111111`（后 6 位）作为基因，放进订单号最低 6 位
- **路由端**：ShardingSphere 自定义分片算法（`DatabaseOrderComplexGeneArithmetic` / `TableOrderComplexGeneArithmetic`）从 orderNumber 的基因位算库/表索引
- **闭环**：同一用户的订单基因相同 → 按 `order_number` 或按 `user_id` 查询都路由到同一库表；当前配置 2 库 4 表，表基因 2 位 + 库基因 1 位，6 位基因上限支持 8 库 × 8 表
- 序列号用 **6 位掩码（64/ms）**，满 64 个等待下一毫秒——因为序列号左移 6 位后只剩 6 位空间，超出会覆盖 workerId 位段、破坏唯一性

### 3.3 为什么分两条链路各自处理时钟回拨

- `getUid()` 链路（秒级）：回拨 1 秒内 → 拒绝生成并抛异常（预生成缓存兜底，短暂失败）
- `getOrderNumber()` 链路（毫秒级）：回拨 ≤5ms → 等待 2 倍时长重试；>5ms → 拒绝生成

---

## 4. RingBuffer 缓存设计（性能核心）

百度 CachedUidGenerator 的亮点，值得拆开看：

- **环形数组 + 两个指针**：tail（写入位置，生产者用）、cursor（消费位置，消费者用），各自只被单线程读写 → **无锁**
- **PaddedAtomicLong**：指针字段前后填充缓存行（64 字节），避免多核 CPU 伪共享
- **后台填充**：BufferPaddingExecutor 用独立线程批量预生成（`nextIdsForOneSecond` 一次算 8192 个），水位低于 paddingFactor（默认 50%）触发补货
- **拒绝策略**：缓存耗尽且填充失败时，take 抛异常（宁可失败不可重复）

对比直接调雪花：缓存让"生成"和"使用"解耦——生成是批量算（一次位运算出 8192 个），使用是纯内存自增。

---

## 5. Redis 在两条链路里的角色

| 场景 | getUid 链路 | 订单号链路 |
|---|---|---|
| 启动时分配 workerId | Redis INCR（22 bits） | Lua 原子（5+5 bits，1024 组合） |
| 运行中 | **不依赖 Redis**（预生成 + 本地自增） | 不依赖 Redis（workId 已固化） |
| Redis 挂时新实例启动 | **启动失败**（workerId 必须唯一） | 降级 MAC+PID 哈希兜底 |

> 为什么 getUid 不做兜底？→ workerId 全局唯一是硬约束，哈希兜底必然撞号。权衡：牺牲"Redis 挂也能部署"换"workerId 一定不重复"。订单号链路有兜底是因为雪花位段小、碰撞概率相对低——但兜底同样有风险（多实例哈希可能撞），属于"降级可用"的取舍。

Lua 脚本（`lua/workAndDataCenterId.lua`）细节：

- workId 递增到 31 后不再递增，改为递增 dataCenterId（workId 保持 31），共 32×32 = 1024 个唯一组合
- 组合耗尽（第 1025 个实例）或 Redis 数据被清空后才回绕到 0——正常规模不可达，Redis 被 flush 属于运维事故（新旧实例可能同 workId）

---

## 6. 已知不足

- **getUid 启动依赖 Redis**：短暂不可用会自动重试（3 次退避），持续不可用则启动失败（设计权衡，见第 5 节）
- **订单号链路 Redis 挂时的新实例兜底有撞号风险**：MAC+PID 哈希在多实例容器下可能重复（权衡取舍，可接受概率）
- **无集成测试**：Lua 脚本行为、Redis 分配、分片路由依赖真实环境；位布局与唯一性已由单元测试覆盖
- **预留 API**：`parseUid()`（解析 ID 位段）在接口中但当前无业务调用

---

## 7. 拷问点

> 主键为什么不用 UUID？→ UUID 无序、36 字符、索引碎片化严重；雪花有序且 64 位 long 存储友好。
> 为什么订单号要比主键多一套生成器？→ 订单号要带用户基因位供分库分表路由，主键不需要；共用会互相挤占位宽。
> 时钟回拨怎么办？→ 秒级链路拒绝 + 抛异常；毫秒级链路 5ms 内等待重试、超限拒绝；生产靠 NTP 校准避免。
> 序列号耗尽怎么办？→ 等下一毫秒（`tilNextMillis` 自旋），不会复用导致重复。
> 为什么 22 bits workerId？→ 百度原版设计配合 Redis INCR：空间大无需回绕，419 万次分配后才超限（超限启动时报错）。

---

## 附：代码结构速览

```
ticketflow-id-generator-framework/
├── com.baidu.fsg.uid/                 百度 uid-generator 移植（含 ticketflow 定制）
│   ├── UidGenerator                   业务入口接口（getUid/getOrderNumber/parseUid）
│   ├── BitsAllocator                  1+28+22+13 位分配器
│   ├── impl/
│   │   ├── DefaultUidGenerator        基础实现（位分配 + workerId 初始化 + 回拨拒绝）
│   │   └── CachedUidGenerator         缓存实现（RingBuffer + 后台填充）
│   ├── buffer/                        RingBuffer / BufferPaddingExecutor / PaddedAtomicLong
│   └── config/
│       ├── IdGeneratorRedisConfig     RedisTemplate + Redis INCR workerId 分配器
│       └── WorkerNodeConfig           组装 cachedUidGenerator bean
├── com.ticketflow/
│   ├── config/IdGeneratorAutoConfig   雪花链路装配（Lua 分配 → SnowflakeIdGenerator）
│   └── toolkit/
│       ├── SnowflakeIdGenerator       雪花 + 基因法（nextId / getOrderNumber）
│       ├── WorkAndDataCenterIdHandler Redis+Lua 分配 workId/dataCenterId
│       └── WorkDataCenterId           workId/dataCenterId 值对象
└── resources/
    ├── lua/workAndDataCenterId.lua    原子分配脚本
    └── META-INF/...AutoConfiguration.imports
```

## 附：与分片配套

订单分片在 `ticketflow-service-common` 的 `shardingsphere/` 包（`DatabaseOrderComplexGeneArithmetic` / `TableOrderComplexGeneArithmetic`），配置在 order-service 的 `shardingsphere-order-local.yaml`（2 库 4 表）。生成端与路由端基因位约定必须一致：**orderNumber 低 6 位 = userId 低 6 位**。
