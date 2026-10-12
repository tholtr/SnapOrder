# SnapOrder · CampusDash

校园跑腿抢单市场：发单人悬赏（资金托管）→ N 人并发抢单、恰好一人成功 → 超时自动流转 → 送达确认后结算。

业务链路短，但每个环节都压在硬骨头上——抢单正确性可验证（超卖就是错），资金正确性可验证（借贷不平就是错）。

> 📌 本文件为占位版本，各子系统的完整说明见下方「文档」一节。

## 目录结构

```
SnapOrder/
├── snaporder-backend/            # Spring Boot 后端（DDD 洋葱架构）
│   ├── dash-domain/                  # 领域层：聚合、值对象、领域事件
│   ├── dash-application/             # 应用层：用例编排、事务边界
│   ├── dash-infrastructure/          # 基础设施层：持久化、缓存、MQ
│   ├── dash-presentation/            # 接口层：REST / WebSocket
│   ├── dash-shared/                  # 共享内核与工具
│   ├── dash-bootstrap/               # 应用启动入口（默认 8080）
│   ├── dash-worker/                  # 异步任务：超时流转、自动结算、消息重发
│   ├── dash-bench/                   # 压测工具
│   ├── docker/                       # 中间件编排与初始化脚本
│   └── docs/                         # 架构设计、设计演进记录、压测方案
└── snaporder-frontend/           # React 18 + TypeScript + Vite 前端
```

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 / 框架 | JDK 21 · Spring Boot · DDD 洋葱架构 |
| 存储 | MySQL 8 · Redis 7 · ShardingSphere |
| 消息 / 限流 | RocketMQ 5.3.1 · Sentinel |
| 前端 | React 18 · TypeScript · Vite 5 |

## 快速开始

**后端**（默认 8080）

```bash
cd snaporder-backend/docker
docker compose up -d && sleep 15 && ./init-mq.sh   # MySQL + Redis + RocketMQ

cd .. && mvn clean test                            # 中间件未起时集成测试自动 skip
mvn -pl dash-bootstrap spring-boot:run
```

**前端**（5173，Vite 代理 `/api` → 8080）

```bash
cd snaporder-frontend
npm install && npm run dev
```

## 文档

- [后端说明与期次进度](snaporder-backend/README.md)
- [设计演进记录（决策与踩坑）](snaporder-backend/docs/设计演进记录.md)
- [架构设计与技术选型](snaporder-backend/docs/架构设计与技术选型.md)
- [压测方案与容量评估](snaporder-backend/docs/压测方案与容量评估.md)
- [前端说明](snaporder-frontend/README.md)
