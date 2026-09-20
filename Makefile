# =============================================================================
# TicketFlow 常用命令收口
#
#   make help                      查看全部目标
#   make up                        打包 + 构建镜像 + 启动整套（等价 ./docker/build.sh）
#   make images                    只打包 + 构建镜像，不启动
#   make down / ps / logs          容器停止 / 状态 / 日志（logs 需 SVC=xxx）
#   make build / test / itest      后端打包 / 单测 / 集成测试
#   make ci                        本地等价 CI（单测 + 集成 + coupon-seckill）
# =============================================================================
SHELL := /bin/bash
COMPOSE := docker compose -f docker/docker-compose.yml
SVC ?= program

.DEFAULT_GOAL := help
.PHONY: help pay-env up images down ps logs restart build test itest coupon-test front-build lint ci clean

help:
	@echo "TicketFlow make 目标："
	@echo "  pay-env        生成 docker/app/pay.env（不存在时，从 .example 复制）"
	@echo "  up             打包 + 构建镜像 + 启动整套系统"
	@echo "  images         只打包 + 构建镜像，不启动容器"
	@echo "  down / ps      停止 / 查看容器状态"
	@echo "  logs           查看服务日志，例：make logs SVC=program"
	@echo "  restart        重启某服务，例：make restart SVC=order"
	@echo "  build          后端 Maven 打包（跳过测试）"
	@echo "  test           后端单元测试"
	@echo "  itest          集成测试（order / program，Testcontainers）"
	@echo "  coupon-test    coupon-seckill 模块测试"
	@echo "  front-build    构建前端（vue3 与 admin）"
	@echo "  lint           静态门禁（当前为编译检查；spotless 未接入）"
	@echo "  ci             本地等价 CI：test + itest + coupon-test"
	@echo "  clean          清理 Maven target 与暂存 jar"

pay-env:
	@if [ ! -f docker/app/pay.env ]; then \
		cp docker/app/pay.env.example docker/app/pay.env; \
		echo "已生成 docker/app/pay.env（本地占位密钥，已 gitignore）"; \
	fi

up: pay-env
	./docker/build.sh

images: pay-env
	./docker/build.sh images

down:
	./docker/build.sh down

ps:
	./docker/build.sh ps

logs:
	./docker/build.sh logs $(SVC)

restart:
	$(COMPOSE) restart $(SVC)

build:
	mvn -B -DskipTests -T 4 package

test:
	mvn -B test

itest:
	mvn -B verify -pl ticketflow-server/ticketflow-order-service,ticketflow-server/ticketflow-program-service -am

coupon-test:
	mvn -B -f coupon-seckill/pom.xml test

front-build:
	cd vue3 && npm install --legacy-peer-deps && npm run build
	cd ticketflow-front-manage && pnpm install && pnpm run build

# 当前仓库未接入 spotless（仅有残留的版本属性），静态门禁退化为编译检查。
lint:
	mvn -B -DskipTests -T 4 compile

ci: test itest coupon-test

clean:
	mvn -B clean
	rm -rf docker/app/jars
