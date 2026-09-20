#!/bin/sh
# =============================================================================
# TicketFlow 应用容器统一入口
#
# 1) 未显式指定 NACOS_DISCOVERY_IP 时，自动取容器自身 IP 用于 Nacos 服务注册，
#    使同一 compose 网络内的服务可以通过注册中心互相访问
#    （裸机本地运行时保持 application.yml 默认 127.0.0.1，不受影响）。
# 2) 检测 OpenTelemetry Java Agent（/otel/opentelemetry-javaagent.jar，由 compose
#    ./otel volume 挂载）：存在且 ENABLE_OTEL!=false 时追加 -javaagent 挂载；
#    缺失时不影响原有启动（仅无 Trace）。agent 下载见 docker/otel/download-agent.sh。
# 3) 以 JAVA_OPTS + 命令行参数启动 Spring Boot 应用。
# =============================================================================
set -e

if [ -z "${NACOS_DISCOVERY_IP}" ]; then
  NACOS_DISCOVERY_IP="$(hostname -i 2>/dev/null | awk '{print $1}')"
  export NACOS_DISCOVERY_IP
fi

OTEL_JAVAAGENT=/otel/opentelemetry-javaagent.jar
OTEL_ARGS=""
OTEL_CONFIG_ARGS=""
if [ -f "${OTEL_JAVAAGENT}" ] && [ "${ENABLE_OTEL:-true}" != "false" ]; then
  OTEL_ARGS="-javaagent:${OTEL_JAVAAGENT}"
  echo "[entrypoint] OpenTelemetry javaagent detected -> enabling OTLP tracing (service=${OTEL_SERVICE_NAME:-unknown})"
  # 声明式配置（如 DROP /actuator/* 的 SERVER span）：以 JVM 系统属性方式传给 agent（与官方 -Dotel.config.file 用法一致）
  if [ -n "${OTEL_CONFIG_FILE:-}" ] && [ -f "${OTEL_CONFIG_FILE}" ]; then
    OTEL_CONFIG_ARGS="-Dotel.config.file=${OTEL_CONFIG_FILE}"
    echo "[entrypoint] declarative config: ${OTEL_CONFIG_FILE}"
  fi
else
  echo "[entrypoint] opentelemetry-javaagent.jar not found at ${OTEL_JAVAAGENT} or ENABLE_OTEL=false -> running WITHOUT OTel agent"
fi

echo "[entrypoint] starting app on ${HOSTNAME} (ip=${NACOS_DISCOVERY_IP}) ..."
# shellcheck disable=SC2086
exec java ${OTEL_CONFIG_ARGS} ${OTEL_ARGS} ${JAVA_OPTS} -jar /app/app.jar "$@"
