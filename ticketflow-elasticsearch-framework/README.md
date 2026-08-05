# ticketflow-elasticsearch-framework — 模块讲解

> 本文档是模块内部讲解，说明"这个模块解决什么问题、怎么设计的、踩过什么坑、还能怎么改"。所有内容对应仓库实际代码，可直接翻源码对照。

---

## 1. 这个模块解决什么问题

### 1.1 业务痛点

项目是演出票务系统（ticketflow）。节目数据在 MySQL 里，比如 `d_program`（节目表）+ `d_program_show_time`（场次表），查询时还要关联分类名、地区名、票档价格区间。

用户端有两个高频读场景，直接查 MySQL 扛不住：

- **搜索**：按标题、演员模糊搜索。MySQL 只能 `LIKE '%关键词%'`，不走索引、全表扫，数据量上来就慢。
- **列表/推荐**：首页分类推荐、详情页"看了这个还看那个"，需要按热度/开场时间排序，甚至要随机。

所以引入 **Elasticsearch 做查询层**：启动时把 MySQL 节目数据全量同步进 ES，之后搜索、列表、推荐都查 ES。

### 1.2 模块的边界（一句话）

```
BusinessEsHandle = 对 ES RestClient 的薄封装
  ├─ 索引生命周期：创建（含 mapping）/ 检查 / 删除
  ├─ 文档操作：写入（单条/指定 ID）/ 批量写入（Bulk API）/ 按 _id 删除 / 按条件查询 / 分页查询
  ├─ 查询能力：term / terms / match（分词）/ range（时间范围）组合，排序，高亮
  └─ 两个开关：esSwitch（整体降级）、esTypeSwitch（ES 6.x/7.x 兼容）
```

业务层（program-service 的 `ProgramEs`）只负责"把业务条件翻译成 `EsDataQueryDto` 列表"，DSL 拼接、响应解析都在框架层完成。

### 1.3 为什么不用 spring-data-elasticsearch 全家桶

这是最常被追问的设计决策。三个理由：

1. **版本魔咒（真实踩过）**。ES 7.x 时代对应 spring-data-elasticsearch 4.x，API 是 `org.elasticsearch.index.query.QueryBuilders` 这一套。但 Spring Boot 3.x 的 dependencyManagement 会把 spring-data-elasticsearch 抬到 5.x，5.x 换成了全新客户端（`co.elastic.clients`），**API 完全断裂**。本项目用过直接依赖锁 4.0.9.RELEASE 来规避：
   - 框架模块里声明了 4.0.9.RELEASE，但**传递依赖不豁免 dependencyManagement**——只要调用方（program-service）不显式声明，版本就被 Boot 覆盖成 5.3.0，`org.elasticsearch.index.query` 直接编译失败。这是实测踩出来的坑：删掉 program-service 的显式声明后立刻编译报错，被迫回滚。
2. **业务只需要"查 + 写"两类动作**。Repository 抽象、ElasticsearchTemplate 的收益很低，反而是它内部连 ES 的方式（走 transport 还是 HTTP、用哪个版本客户端）不可控。
3. **RestClient + 手写 DSL 依赖更小、行为透明**。每个请求长什么样自己完全清楚，出问题好排查。

### 1.4 拷问点

> 为什么 ES 而不是 Redis 搜索？→ 搜索是分词、相关性排序的场景，Redis 做不了；ES 存的是文档 + 倒排索引。
> 为什么不用中文分词插件？→ 当前 matchQuery 走标准分词，中文按单字切分，语义精度一般。

---

## 2. 数据怎么进去的（索引初始化与同步）

### 2.1 启动初始化链

program-service 有 4 个启动初始化器，按 order 顺序执行：

```
order=1  ProgramCategoryInitData       先初始化分类数据
order=2  ProgramShowTimeRenewal        检查演出时间过期 → 过期则删 ES 索引（触发重建）+ 清 Redis/本地缓存
order=3  ProgramElasticsearchInitData  全量同步 MySQL 节目数据 → ES
order=4  ProgramBloomFilterInit        布隆过滤器（与 ES 无关）
```

设计点：**节目演出时间过期是个低频事件**，走"删索引 → order=3 全量重建"最省事；如果做增量更新，就得记录每个节目上次同步状态，复杂且没必要。

### 2.2 全量同步流程（ProgramElasticsearchInitData）

```
1. 删旧索引（存在才删）+ 建新索引（带 mapping），失败则本次同步放弃
2. 从 DB 查全部节目 id（getAllProgramIdList）
3. 一次性聚合所有节目票档的 MIN/MAX 价格（避免逐条查）
4. 循环组装每个节目的 20 字段 flat map → batchAdd 批量写入（Bulk API，每 500 条一批）
```

几个细节：

- **为什么先删再建而不是 upsert**：启动期一次性行为，数据量在可控范围（几千条），删库重建保证索引干净无残留，且天然处理了"字段结构变化"。
- **mapping 是显式定义的**（`getEsMapping`，20 个字段）。关键：**text 字段自动加 keyword 子字段**（`ignore_above: 256`）——text 默认被分词，无法直接做精确匹配/排序/聚合，加 keyword 子字段才有 `title.keyword` 可用。这是创建索引时最容易漏的点。
- **环境隔离**：索引名是 `SpringUtil.getPrefixDistinctionName() + "-program"`，即 `dev-program`、`prod-program`。开发、测试、生产可以共用一个 ES 集群互不干扰。
- **批量写入**：`batchAdd` 走 Bulk API（`POST /_bulk`，NDJSON 格式，500 条一批），比逐条 POST 减少约 500 倍的 HTTP 往返；响应里解析 `errors` 字段识别部分失败。Content-Type 用 `application/x-ndjson` 且通过请求级 header 覆盖默认的 `application/json`（见 5.1 第 5 条）。`add` 还支持指定文档 ID 的幂等 upsert（`PUT /_doc/{id}`），是增量同步的基础。

### 2.3 查询链路入口的兜底

ProgramService 有 4 个查询入口（selectPage / selectHomeList / search / recommendList），统一模式：

```
查 ES → 结果不为空 → 返回 ES 结果
     → 结果为空/异常 → 回退 MySQL 查询（DB 兜底）
```

这正是第 4 节"可靠性"的主线。

### 2.4 拷问点

> 为什么全量重建而不是增量同步？→ 启动期一次性行为、数据量可控；增量需要维护同步状态（版本号/时间戳），复杂度不划算。数据量上到十万级就该换增量。
> 为什么索引名带环境前缀？→ 多环境共用一个集群时隔离，避免 dev 把 prod 索引删了。

---

## 3. 查询怎么走的

### 3.1 查询条件模型（EsDataQueryDto → DSL）

业务层不直接拼 DSL，而是声明式传条件，框架层翻译：

| EsDataQueryDto 写法 | 生成的 DSL | 说明 |
|---|---|---|
| `paramValue = 单个值` | `termQuery` | 精确匹配（等值） |
| `paramValue = 集合`（analyse=false） | `termsQuery` | IN 查询 |
| `paramValue = 集合`（analyse=true） | `should(matchQuery)` 循环 | 分词 OR 匹配 |
| `paramValue = 值`（analyse=true） | `matchQuery` | 分词匹配 |
| `startTime / endTime` | `rangeQuery(includeLower=true)` | 时间范围（≥开始） |

所有条件挂在 `BoolQueryBuilder.must` 下 = **AND 组合**。排序通过 `sortParam + sortOrder` 参数（`FieldSortBuilder`），不传 sortParam 默认 DESC 但实际不排序。

细节：`trackTotalHits(true)` 必开。ES 7.x 默认只精确统计前 10000 条，超过就返回 `total: 10000`，**分页总页数会算错**——开启后拿到的才是真实总数。

### 3.2 四个业务查询场景（ProgramEs）

| 场景 | 查询逻辑 | 亮点 |
|---|---|---|
| `selectPage` 分页列表 | 按 area/分类/时间过滤 + type=2/3/4 排序（热度/开场时间/上架时间） | 排序参数化 |
| `search` 全文搜索 | `matchQuery(title) OR matchQuery(actor)` + 高亮 | 高亮片段用 `<em>` 包裹，前端直接标红 |
| `selectHomeList` 首页推荐 | 循环 4 个父分类，每类取 7 条 | N+1 个小查询，数据量小可接受 |
| `recommendList` 猜你喜欢 | `ScriptSort("Math.random()")` 随机取 10 条，可按 programId 排除已看 | 随机排序避免每次一样 |

### 3.3 响应解析（executeQuery）

ES 返回的 JSON 解析成业务对象，过程中做 4 件事：

1. **total 解析兼容 6.x/7.x**：6.x 的 `hits.total` 是数字，7.x 变成 `{value, relation}` 对象，按 `esTypeSwitch` 分支取数。
2. **esId 回填**：把文档内部 `_id` 塞进返回实体（`ProgramListVo.esId`）。业务上按 programId 删除文档时，必须先查出 esId 再删——ES 删除按 `_id`，不按业务字段。
3. **sort 值回填**：如果查询带了排序，ES 会返回每条的 sort 值，回填到实体的 `sort` 字段。
4. **高亮回填**：命中字段用高亮片段替换原值（`<em>周杰伦</em>` 演唱会）。

反序列化用 fastjson：`JSONObject.parseObject(json, clazz)`，字段名靠 mapping 字段与实体字段同名映射。

### 3.4 拷问点

> 分页为什么用 from/size？→ 数据量小（几千条）from/size 够用，远未到 ES 单次查询 10000 条的默认上限。
> 高亮是前端还是后端做的？→ 后端 ES 高亮返回 `<em>` 片段，前端直接渲染。比前端自己高亮省事且一致。

---

## 4. 可靠性怎么保证

### 4.1 条件装配：没配 ES 也能启动

```java
@ConditionalOnProperty(value = "elasticsearch.ip")
public class BusinessEsAutoConfig { ... }
```

**配了 `elasticsearch.ip` 才创建 RestClient 和 BusinessEsHandle**。不配则 bean 不存在。

- 初始化器用 `@Autowired(required = false)` 弱依赖：bean 不存在时直接跳过初始化，并打 warn 日志。
- 配置文件里 `${ES_USERNAME:default}`：**认证哨兵机制**——userName/passWord 等于 `default`（或不配环境变量）时不启用认证，本地 ES 免认证直接跑；生产注入真实账号才带 BasicAuth。这样本地开发零成本、生产自动启用认证。

### 4.2 esSwitch：整体降级开关

`esSwitch` 默认 true。为 false 时 **BusinessEsHandle 所有公开方法入口直接返回空**（不抛异常、不连 ES）。

结合 DB 兜底，效果是：

```
esSwitch=true  + ES 正常   → 查 ES
esSwitch=true  + ES 挂了   → ES 返回空 → DB 兜底（功能不挂，慢一点）
esSwitch=false（无 ES 环境）→ 所有查询直接空 → DB 兜底（完全降级）
```

为什么开关做在方法入口而不是装配层？——开关和连接是两回事：装配层决定"有没有 ES 客户端"，开关决定"业务要不要用"。做成配置项，运维改一行 yml 就能整体切换，不用改代码。

### 4.3 兜底的代价（诚实说明）

ES 查询失败被 catch 后**只打日志、返回空**。好处是与 DB 兜底配合实现"永不挂"；坏处是**问题被静默吞掉**——ES 挂了业务看不到异常，只能看日志发现。这是刻意取舍：查询侧宁可降级也不失败，写入侧（初始化同步）失败会打 error 并中止。

### 4.4 esTypeSwitch：6.x/7.x 兼容

ES 6.x 有 type 概念，7.x 移除。`esTypeSwitch=false`（默认）走 7.x；切到 true 兼容 6.x 老集群。影响三个分支点：

| 分支点 | 6.x（esTypeSwitch=true） | 7.x（false） |
|---|---|---|
| 查询/写入 endpoint | `/{index}/{type}/_search`、`/{index}/{type}` | `/{index}/_search`、`/{index}/_doc` |
| mapping 结构 | `mappings.{type}.properties` | `mappings.properties` |
| `hits.total` | 数字 | `{value, relation}` 对象 |

本质是**用最小分支覆盖两代 API 差异**，一个开关切换，代码里没有任何硬编码版本假设。

### 4.5 拷问点

> ES 挂了为什么用户无感？→ 因为查询失败返回空集合，ProgramService 检测到空就走 MySQL。代价是 ES 的排序/搜索能力降级成 MySQL 的简单查询（搜索词不生效、推荐变热门榜），属于"功能降级而非不可用"。
> 为什么不直接让 ES 查询抛异常？→ 那用户请求就 500 了。电商类读场景"降级可用"优于"强一致报错"。

---

## 5. 踩过的坑和怎么优化

### 5.1 真实踩过的坑

1. **版本混用：rest-client 8.13.4 vs elasticsearch core 7.6.2**。父 pom 的 dependencyManagement 把 rest-client 抬到 8.13.4，而 spring-data-elasticsearch 4.x 带的是 7.6.2 全家。实测 8.13.4 的 jar 里不含 `org/elasticsearch/core` 类（拆包了），运行时无冲突，但构建层面不干净，属于技术债。
2. **传递依赖版本被覆盖**（第 1.3 节）：删掉 program-service 对 spring-data-elasticsearch 的显式声明，版本立刻被 Boot 的 dependencyManagement 抬到 5.3.0，`org.elasticsearch.index.query` 编译失败。教训：**锁定版本必须放在每个使用方的直接依赖里，不能指望传递**。
3. **`hits.total` 格式变化**：7.x 之后 total 是对象不是数字，直接 `getLong("total")` 会拿到 null，分页 total 变 0。解析按版本分支处理。
4. **include_type_name**：6.x 的 mapping 创建方式在 7.x 会报错，8.x 直接移除；统一走 7.x 的 `mappings.properties` 结构。
5. **_bulk 的 Content-Type 必须是 `application/x-ndjson`**：RestClient 默认 header 是 `application/json`，直接发会被 ES 拒绝。通过请求级 `RequestOptions` 的 header 覆盖——rest-client 源码行为是"同名请求级 header 跳过默认 header"（`InternalRequest.setHeaders`），不会出现双 Content-Type。

### 5.2 已知不足

- **全量重建策略**：每次启动删索引重建全量数据，数据量上去后启动同步时间长。增量同步的框架能力已具备（`add` 支持指定文档 ID 幂等 upsert、`deleteByProgramId` 删除），DB 侧 `Program` 也有 `editTime` 变更时间字段，但业务链路未接。
- **无集成测试**：ES 的响应行为（total 格式、高亮、状态码）依赖真实集群，目前靠代码分支覆盖 + 手工验证，没有自动化测试。

### 5.3 优化方向

1. **增量同步** —— 启动时只同步"有变更"的节目（DB `editTime` 过滤），写入按 programId 指定文档 ID upsert；配合已有删除路径形成完整闭环。

### 5.4 拷问点

> 如果让你重构这个模块，先动哪里？→ 先做增量同步解决启动耗时（框架能力已齐，只差业务链路）。

---

## 附：配置项速查表

| 配置（前缀 `elasticsearch.`） | 默认值 | 说明 |
|---|---|---|
| `ip` | 无（必配，配了才激活模块） | ES 地址数组，如 `127.0.0.1:9200` |
| `userName` / `passWord` | `default` | 等于 `default` 不启用认证 |
| `esSwitch` | `true` | false 时所有 ES 操作静默返回空，走 DB 兜底 |
| `esTypeSwitch` | `false` | true 兼容 ES 6.x（type 模式） |
| `connectTimeOut` / `socketTimeOut` / `connectionRequestTimeOut` | `40000` | 超时（毫秒） |
| `maxConnectNum` | `400` | HTTP I/O 线程数 |

## 附：代码结构速览

```
ticketflow-elasticsearch-framework/
├── BusinessEsAutoConfig     条件装配 + RestClient 构建（认证/超时/线程）
├── BusinessEsProperties     配置绑定（elasticsearch.* 前缀）
├── BusinessEsHandle         核心操作门面（索引/CRUD/批量写入/查询/解析，esSwitch 入口检查）
└── dto/
    ├── EsDataQueryDto       查询条件模型（字段值/时间范围/是否分词）
    └── EsDocumentMappingDto mapping 字段定义（字段名 + ES 类型）
```
