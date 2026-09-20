# OpenTelemetry 接入说明（Trace 链路）

本目录用于放置 `opentelemetry-javaagent.jar`。放置后，`docker-compose` 中 8 个应用容器
（gateway/user/base-data/customize/program/order/pay/admin）会自动挂载 agent，
链路数据经 **OTLP HTTP**（`http://jaeger:4318`）上报内置的 **Jaeger** 演示后端。

## 快速开始

```bash
# 1. 下载 agent（默认稳定版 v2.31.1，官方 sha256 校验）
./docker/otel/download-agent.sh

# 2. 重新构建并启动整套系统（8 个应用 + 基础设施 + jaeger）
./docker/build.sh

# 3. 查看链路
#    Jaeger UI   : http://localhost:16686   （按服务选 gateway → Find Traces）
#    Prometheus  : http://localhost:9090
#    Grafana     : http://localhost:3000    （admin/admin）
```

## 工作机制

- 应用镜像入口 `docker/app/entrypoint.sh` 检测 `/otel/opentelemetry-javaagent.jar`
  （compose 将本目录只读挂载到容器 `/otel`）。**文件存在且 `ENABLE_OTEL!=false` 才追加
  `-javaagent`**；文件缺失时应用照常启动，仅无 Trace —— 不影响原有开发流程。
- 公共配置位于 `docker-compose.yml` 的 `x-app-env` 锚点：
  - `OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4318` —— Trace 出口；
  - `OTEL_SERVICE_NAME=<服务名>` —— 各服务独立设置（Jaeger 依赖图/服务检索用）；
  - `OTEL_METRICS_EXPORTER=none` / `OTEL_LOGS_EXPORTER=none` —— 本阶段 Trace-only：
    Metrics 仍由 `/actuator/prometheus` 提供给 Prometheus/Grafana（保持 benchmark 兼容），
    日志仍落本地文件。
- 日志关联：agent 自动向 log4j2 MDC 注入 `trace_id`/`span_id`，与各服务 log4j2.xml
  pattern（`[%X{trace_id}] [%X{span_id}]`）配合，实现 **日志行 ↔ Trace** 互查。

## 降噪：声明式配置（otel-agent-config.yaml）

`docker/otel/otel-agent-config.yaml` 与 agent 同目录、随 `./otel:/otel` volume 挂载进容器，
经 `OTEL_CONFIG_FILE`（compose x-app-env）+ entrypoint 以 `-Dotel.config.file=` 加载：

- 用 v2.x **内置**的 `rule_based_routing` 采样器 **DROP 所有 `/actuator/*` 的 SERVER span**
  （含子 span），消除 Prometheus 每 15s 抓取与健康检查的 trace 噪音；业务请求走 `always_on` 不受影响。
- ⚠️ **已知坑（重要）**：declarative config 一旦提供会**整体接管 SDK 资源配置**，
  `OTEL_EXPORTER_OTLP_ENDPOINT` / `OTEL_SERVICE_NAME` 等环境变量不再自动生效，
  因此配置文件内必须显式声明 resource（detector 读 `OTEL_SERVICE_NAME`）与 trace exporter
  （endpoint 以 `${OTEL_EXPORTER_OTLP_ENDPOINT:-http://jaeger:4318}` 引用环境变量）。
- config 在 JVM 启动时读取，改动后需 `docker compose up -d --force-recreate <svc>`。

## Agent 覆盖范围（本项目自动插桩的调用点）

- HTTP 入口/出口：Spring MVC（各服务）、Spring Cloud Gateway（WebFlux/Netty）、Feign(OkHttp)
- 数据访问：JDBC（含 ShardingSphere 路由后的真实 SQL 执行）、MyBatis
- 中间件：Kafka（producer/consumer，消息 header 自动携带 traceparent —— 打通"下单受理 →
  MQ → 消费建单"的异步链路）、Redis（Lettuce 等）
- 线程池/异步：常见 Executor 的 context 传播

> Redisson（基于 Netty 封装）命令级插桩覆盖有限；如后续需要 Redis Lua 扣减段的精确耗时，
> 可在代码中用手动 Span/@WithSpan 补充（见方案 01 的取舍记录）。

## 关闭 / 卸载

- 单次关闭：任意应用设置环境变量 `ENABLE_OTEL=false`（仍保留挂载文件）。
- 彻底卸载：删除本目录 jar 并 `./docker/build.sh up -d`（或直接 `docker compose down`），
  不影响任何业务功能。

## 版本升级

```bash
OTEL_AGENT_VERSION=vX.Y.Z ./docker/otel/download-agent.sh
```
