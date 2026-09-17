# ==========================================================================
# CampusHub AI — 常用命令入口
# ==========================================================================
# 目的：把「怎么启动、怎么测、怎么构建」固化成可复制的命令，
#      避免每个人记不同的一套命令，也避免 README 与实际操作脱节。
#
# 前置：本机需要能直接用到 java / mvn / docker / node。
#      · 本项目自带 Maven Wrapper（./mvnw），不需要全局 Maven，但仍需 JAVA_HOME 指向 JDK 21
#      · 本机（desk 环境）请先执行： source "$HOME/Desktop/desk/env.sh"
#
# ⚠️ 本机限制（2026-09-17 实测，详见 docs/11-开发环境.md §2.1）：
#    托管运行时通过 NODE_OPTIONS 注入的文件系统代理会拒绝 npm 的落盘操作。
#    因此前端目标需要显式清空它：NODE_OPTIONS= make fe-install
#    （普通开发机无需此操作。）
#
# 用法：make help
# ==========================================================================

# 若存在 .env 则加载并导出（供 docker compose 与 Spring 读取）
-include .env
export

SHELL := /bin/bash
.DEFAULT_GOAL := help

COMPOSE := docker compose

## ---------------------------------------------------------------------------
## help：列出所有可用目标
## ---------------------------------------------------------------------------
.PHONY: help
help:
	@echo "CampusHub AI — 可用命令"
	@echo ""
	@echo "  依赖环境"
	@echo "    make up            启动依赖（当前仅 MySQL）并等待健康"
	@echo "    make down          停止依赖，保留数据卷"
	@echo "    make reset-data    停止依赖并删除数据卷（会清空数据库！）"
	@echo "    make logs          跟踪依赖日志"
	@echo "    make ps            查看依赖状态"
	@echo ""
	@echo "  后端"
	@echo "    make build         构建（clean package，跳过测试）"
	@echo "    make test          只跑单元测试（*Test）"
	@echo "    make it            只跑集成测试（*IT，需要 Docker）"
	@echo "    make verify        完整校验：单元测试 + 集成测试"
	@echo "    make run           本地启动应用（local profile）"
	@echo ""
	@echo "  前端"
	@echo "    make fe-install    安装前端依赖"
	@echo "    make fe-dev        启动前端开发服务器"
	@echo "    make fe-build      构建前端产物"
	@echo ""
	@echo "    make clean         清理构建产物"

## ---------------------------------------------------------------------------
## 依赖环境
## ---------------------------------------------------------------------------
.PHONY: up
up:
	$(COMPOSE) up -d --wait
	@echo "依赖已就绪。数据库端口：$${MYSQL_PORT:-3306}"

.PHONY: down
down:
	$(COMPOSE) down

.PHONY: reset-data
reset-data:
	@echo "⚠️  这会删除 Docker 数据卷 camphub-mysql-data，数据库内容将不可恢复。"
	@echo "    如需继续，请显式执行： docker volume rm camphub-mysql-data && $(COMPOSE) down"
	@exit 1

.PHONY: logs
logs:
	$(COMPOSE) logs -f

.PHONY: ps
ps:
	$(COMPOSE) ps

## ---------------------------------------------------------------------------
## 后端
## ---------------------------------------------------------------------------
.PHONY: build
build:
	./mvnw -B clean package -DskipTests

.PHONY: test
test:
	./mvnw -B test

.PHONY: it
it:
	./mvnw -B failsafe:integration-test failsafe:verify

.PHONY: verify
verify:
	./mvnw -B clean verify

.PHONY: run
run:
	./mvnw -B spring-boot:run

## ---------------------------------------------------------------------------
## 前端
## ---------------------------------------------------------------------------
.PHONY: fe-install
fe-install:
	cd frontend && npm install

.PHONY: fe-dev
fe-dev:
	cd frontend && npm run dev

.PHONY: fe-build
fe-build:
	cd frontend && npm run build

## ---------------------------------------------------------------------------
## 清理
## ---------------------------------------------------------------------------
.PHONY: clean
clean:
	./mvnw -B clean
	rm -rf frontend/dist frontend/node_modules/.vite
