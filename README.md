# SnapOrder · CampusDash

校园跑腿抢单市场：发单人悬赏（资金托管）→ N 人并发抢单、恰好一人成功 → 超时自动流转 → 送达确认后结算。

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
