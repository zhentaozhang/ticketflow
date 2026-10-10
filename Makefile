# =============================================================================
# TicketFlow 常用命令收口
#
#   make help                      查看全部目标
#   make up                        打包 + 构建镜像 + 启动整套（等价 ./docker/build.sh）
#   make images                    只打包 + 构建镜像，不启动
#   make down / ps / logs          容器停止 / 状态 / 日志（logs 需 SVC=xxx）
#   make build / test / itest      后端打包 / 单测 / 集成测试
#   make ci                        本地等价 CI（单测 + 集成）
# =============================================================================
SHELL := /bin/bash
COMPOSE := docker compose -f docker/docker-compose.yml
SVC ?= program
ARGS ?= --help

.DEFAULT_GOAL := help
.PHONY: help pay-env up images down ps logs restart build test itest front-build lint checkstyle spotbugs deps tfctl cd verify rollback scan ci sink alert-test clean

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
	@echo "  front-build    构建前端（vue3 与 admin）"
	@echo "  lint           静态门禁（编译 + Checkstyle，命中即失败）"
	@echo "  checkstyle     Checkstyle 静态检查（强制门禁）"
	@echo "  spotbugs       SpotBugs 缺陷扫描（High 阈值，强制门禁）"
	@echo "  deps           打印依赖树（mvn dependency:tree）"
	@echo "  tfctl          效能 CLI，例：make tfctl ARGS=diag"
	@echo "  scan           镜像漏洞扫描（Trivy，HIGH/CRITICAL 卡口）"
	@echo "  cd             本地 CD：构建→载入 kind→滚动更新→发布验证（失败自动回滚）"
	@echo "  verify         发布验证（rollout + 镜像 tag + gateway health）"
	@echo "  rollback       回滚到上一版本（kubectl rollout undo）"
	@echo "  ci             本地等价 CI：test + itest"
	@echo "  sink           启动本地告警接收器（scripts/alert-sink.py :8089）"
	@echo "  alert-test     向 Alertmanager 发送一条测试告警（验证告警闭环）"
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

front-build:
	cd vue3 && npm install --legacy-peer-deps && npm run build
	cd ticketflow-front-manage && pnpm install && pnpm run build

# 静态门禁：编译 + Checkstyle，命中即失败（基线已清零）。历史遗留 spotless/ 配置不启用。
lint:
	mvn -B -DskipTests -T 4 compile
	mvn -B checkstyle:check

checkstyle:
	mvn -B checkstyle:check

# SpotBugs 强制门禁（High 阈值）；豁免项见 config/spotbugs/exclude.xml
spotbugs:
	mvn -B -DskipTests compile spotbugs:check

deps:
	mvn -B dependency:tree

tfctl:
	python3 tools/tfctl/tfctl.py $(ARGS)

scan:
	bash scripts/scan-images.sh

cd:
	bash scripts/cd.sh

verify:
	bash scripts/release-verify.sh

rollback:
	bash scripts/rollback.sh

ci: test itest

sink:
	python3 -u scripts/alert-sink.py 8089

alert-test:
	@curl -s -XPOST http://localhost:9093/api/v2/alerts \
		-H 'Content-Type: application/json' \
		-d '[{"labels":{"alertname":"ManualTest","severity":"P0","programId":"9999"},"annotations":{"summary":"manual alert test"}}]' \
		&& echo " -> 测试告警已发送（接收端: make sink；Alertmanager UI: http://localhost:9093）"

clean:
	mvn -B clean
	rm -rf docker/app/jars
