# =============================================================================
# TicketFlow 应用服务统一运行镜像
#
# 所有后端服务共用本 Dockerfile，通过 build args 区分：
#   SVC   —— 服务名（决定拷贝 docker/app/jars/<SVC>/app.jar）
#   PORT  —— 容器内暴露端口（仅文档用途；端口映射由 compose ports 决定）
#
# 构建方式：宿主机先 `mvn package`，由 docker/build.sh 暂存 fat-jar，
#           并在暂存阶段把 ShardingSphere 配置 127.0.0.1:3306 -> mysql:3306。
#
# 基础镜像：官方 temurin JRE（Debian jammy）。如需离线/受限网络环境，
#           可用 --build-arg BASE_IMAGE=<本地已有镜像> 覆盖。
# =============================================================================
ARG BASE_IMAGE=eclipse-temurin:17-jre-jammy
FROM ${BASE_IMAGE}

ARG SVC
ARG PORT

# curl：供 compose healthcheck 使用；同时创建非 root 运行用户。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -g 1000 app \
    && useradd -m -u 1000 -g app app

ENV TZ=Asia/Shanghai \
    APP_PORT=${PORT} \
    JAVA_OPTS="-Xms192m -Xmx384m" \
    NACOS_DISCOVERY_IP=""

WORKDIR /app
COPY docker/app/jars/${SVC}/app.jar /app/app.jar
COPY docker/app/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh \
    && mkdir -p /app/logs \
    && chown -R app:app /app

USER app
EXPOSE ${APP_PORT}
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
