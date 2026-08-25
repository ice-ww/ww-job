# ww-job 轻量分布式任务调度平台 — 设计文档

> 日期：2026-08-25
> 状态：已评审，待进入实现计划
> 定位：对标 xxl-job 精简版，作为后端开发实习项目

---

## 1. 项目概述

ww-job 是一个**轻量分布式任务调度平台**，采用「调度中心 + 执行器」双角色架构。目标是实现一个可以独立部署、分布式执行定时任务的完整系统，并在不引入 ZK/Nacos/MQ 等重中间件的前提下，把分布式调度的核心链路（时间轮、自研注册中心、分布式锁、路由、分片、阻塞控制）吃透、讲清。

**核心价值主张**：轻量（只依赖 MySQL + Redis）、核心自研（调度引擎、注册中心、路由、分片全自己实现）、面试可深度展开。

---

## 2. 需求

### 2.1 核心链路（Phase 1，必须）

1. 任务 CRUD：任务名称、Handler、参数、cron、路由策略、阻塞策略、重试、超时
2. cron 定时触发 + 手动触发
3. 执行器注册 / 心跳 / 超时剔除
4. 任务分发（按路由策略选择执行器）
5. 执行日志收集与查看
6. 失败重试

### 2.2 增强功能（Phase 2）

1. 失败报警（钉钉 / 企微 Webhook + 邮件可选）
2. 阻塞策略 + 并发控制
3. 分片广播任务

### 2.3 前端

Vue3 + Element Plus 管理后台：任务管理、执行日志、手动触发、执行器列表。

### 2.4 非目标（Non-Goals，明确不做）

- 不引入 ZK / Nacos / Etcd 等外部注册中心
- 不引入通用 MQ 做任务分发（RocketMQ 仅作为 Phase 3 可选的「延迟任务 / 结果异步消费」能力）
- 不做任务依赖编排（DAG 工作流）
- 不做多租户 / 复杂权限体系
- 不做海量任务（百万级/秒）的高性能优化

---

## 3. 技术选型

| 维度 | 选型 | 说明 |
|------|------|------|
| 语言 | Java 17 | 当前主流 LTS |
| 框架 | Spring Boot 3.3 + Maven | 主流、生态成熟 |
| 存储 | MySQL 8 + MyBatis-Plus | 主流、上手快 |
| 缓存/锁 | Redis | 分布式锁防重复触发 + 缓存 |
| 注册中心 | 自研（内置注册表 + 心跳） | 轻量，不用 ZK/Nacos |
| 通信 | HTTP（执行器内嵌 HTTP server） | 请求-响应式，xxl-job 同款 |
| 调度引擎 | 自研时间轮（TimeWheel） | 项目灵魂，面试亮点 |
| cron 解析 | Spring 内置 CronExpression | 零额外依赖 |
| 报警 | 钉钉/企微 Webhook + 策略模式 | 简单、体现设计 |
| 前端 | Vue3 + Element Plus + Vite | 主流 |
| 部署 | Docker Compose | 演示分布式 |

---

## 4. 系统架构

### 4.1 角色

- **ww-job-admin（调度中心）**：任务管理、调度触发、路由分发、注册表维护、日志收集、失败重试、报警
- **ww-job-executor（执行器）**：注册/心跳、接收任务、执行 JobHandler、回传日志与结果

### 4.2 模块划分（Maven 多模块）

```
ww-job
├── ww-job-core              # 公共内核：通信协议 DTO、时间轮、路由策略接口、
│                            #   IJobHandler 抽象、阻塞策略、通用工具
├── ww-job-admin             # 调度中心（Spring Boot 应用）
├── ww-job-executor          # 执行器核心（Spring Boot Starter，业务方引入）
├── ww-job-executor-samples  # 示例执行器（演示用，含示例 JobHandler）
└── ww-job-admin-ui          # 前端（Vue3 + Element Plus + Vite）
```

---

## 5. 核心数据流

1. **注册/心跳**：执行器启动 → 注册（appName + ip:port）→ 每 30s 心跳 → 90s 无心跳剔除
2. **调度触发**：调度线程扫 DB 中「未来 5s 窗口内到期」任务 → cron 算下次触发时间并原子更新 → 投入时间轮 → 到点触发
3. **任务分发**：按路由策略选执行器 → HTTP 调执行器 `/run`
4. **任务执行**：执行器查 JobHandler（Spring Bean）→ 线程池执行 → 回传日志 + 结果
5. **失败处理**：失败 → 按 retryCount 重试 → 超限触发报警

---

## 6. 核心设计

### 6.1 调度引擎：时间轮（TimeWheel）

**职责分工**：DB 负责「长期记忆」，时间轮只负责「精确等待最后几秒」。

- **调度线程（DB 预读）**：每 ~1s 扫 DB，挑「`nextTriggerTime` 在未来 5s 窗口内」的任务，用 cron 算出下一次触发时间并原子更新回 DB，再投入时间轮
- **时间轮（精确触发）**：`tickDuration = 1s`、`wheelSize = 60`（一圈 60s），环形数组 + 槽位链表；独立线程每 tick 推进一格，执行到点任务

**要点**：
- 3 小时后的任务始终只在 DB，直到进入 5s 窗口才加载进时间轮——时间轮里永远只有「几秒内即将触发」的任务
- 「发现任务」（DB 预读，容忍抖动）与「精确触发」（时间轮，精确到秒）解耦，同时拿到容错与精度
- `remainingRounds` 兜底：若任务延迟超过一圈（如时间轮临时积压）仍能正确处理

### 6.2 自研注册中心

- 执行器启动 `POST /registry` 注册，每 30s 心跳
- 调度中心后台线程每 10s 扫描，90s 无心跳剔除
- 在线列表：DB 持久化 + 内存缓存供路由

**为什么不用 ZK**：调度场景允许秒级失联感知延迟，不需要 ZK 的强一致/Paxos 复杂度；「心跳 + 超时剔除」即等价于临时节点的自动删除。

### 6.3 分布式锁（调度中心多实例去重）

- 调度中心支持多实例；同一任务同一时刻只能被一个实例触发
- 方案：Redis 分布式锁（`SET lock:trigger:{jobId} NX EX`）+ 任务状态机兜底（`WAITING → RUNNING` 原子流转）
- 锁带超时自动释放，防实例宕机死锁

### 6.4 路由策略（策略模式 `Router`）

| 策略 | 说明 |
|------|------|
| 轮询 | 顺序轮转 |
| 随机 | 随机选一台 |
| 故障转移 | 轮询但跳过心跳超时节点 |
| 分片广播 | 所有在线执行器均收到，带 shardIndex/shardTotal（Phase 2） |

### 6.5 阻塞策略（执行器侧）

同一 JobHandler 前次未跑完又来新触发时：

| 策略 | 行为 |
|------|------|
| 串行 | 排队等待 |
| 丢弃后续 | 拒绝新触发 |
| 覆盖之前 | 取消旧的、执行新的 |

实现：`ConcurrentHashMap<jobId, 运行状态>` + 有界线程池/队列控制并发。

### 6.6 分片广播（Phase 2）

调度中心把任务拆成 N 片（N = 在线执行器数）广播，每台按 `shardIndex` 处理自己 1/N 的数据。典型场景：批量数据处理（对账、数据迁移）。

### 6.7 失败重试与报警

- 执行器上报失败 → 按 `retryCount` 重试 → 用尽触发报警
- 报警策略模式 `AlarmHandler`：钉钉/企微 Webhook、邮件（可选）
- 报警内容：任务名、失败原因、执行器、时间、日志入口；短时间去重防轰炸

### 6.8 执行日志收集

- 执行器将日志行写入内存缓冲 → 执行结束（或定时）HTTP 批量回传 → 调度中心落库
- 前端按 `jobLogId` 分页查看；轻量起见不做实时流式推送

---

## 7. 数据模型（4 张核心表）

**`job_info` 任务表**：id, job_name, job_desc, job_group_id, handler_name, executor_param, cron, route_strategy, block_strategy, retry_count, timeout, alarm_config, trigger_status, trigger_next_time, trigger_last_time, create_time, update_time

**`job_group` 执行器分组**：id, app_name, title, address_type(自动注册/手动), address_list, create_time, update_time

**`job_registry` 执行器注册表**：id, job_group_id, registry_key(appName), registry_value(ip:port), heartbeat_time, update_time

**`job_log` 执行日志**：id, job_id, job_group_id, executor_address, trigger_type(cron/manual/retry), trigger_time, handle_time, handle_code(成功/失败), handle_msg, status, create_time

> 执行器侧无自有表，靠 JobHandler（Spring Bean 扫描）注册任务。

---

## 8. 测试策略

- **单元测试**：时间轮（tick 推进、misfire）、cron 计算、路由策略、阻塞策略、分片计算
- **集成测试**：注册/心跳、任务端到端（调度中心 → 执行器 → 结果回传 → 日志落库）
- **手动演示**：Docker Compose 起 1 调度中心 + 3 执行器，演示轮询分发、故障转移

---

## 9. 部署

- `docker-compose.yml`：MySQL + Redis + 1× admin + 3× executor-samples
- 本地开发：IDEA 多模块启动，MySQL/Redis 走 Docker 或本机

---

## 10. 里程碑

| 阶段 | 内容 |
|------|------|
| **Phase 1（核心可用）** | 多模块骨架 → 注册/心跳 → 时间轮调度 → HTTP 分发 → 日志 → 重试 → 前端基础页 |
| **Phase 2（增强完整）** | 阻塞策略+并发控制 → 失败报警 → 分片广播 |
| **Phase 3（可选打磨）** | 登录鉴权 → 监控大盘 → Docker 化完善 → README |

---

## 11. 关键取舍记录（ADR 简记）

| 决策 | 结论 | 理由 |
|------|------|------|
| 通信用 HTTP 而非 RocketMQ | HTTP | 任务分发是请求-响应式，与 MQ 异步模型不匹配；主流调度框架均不用通用 MQ 分发；MQ 留给 Phase 3 延迟任务/结果异步消费 |
| 注册中心自研而非 ZK/Nacos | 自研 | 轻量、无外部依赖；心跳+超时剔除即满足需求 |
| 调度引擎自研时间轮而非 Quartz | 自研时间轮 | 项目灵魂、面试亮点、可控 |
| 分布式锁用 Redis 而非 DB 行锁 | Redis | 清晰、面试好讲；状态机兜底防边界问题 |
| Java 17 + SB 3.3 而非 Java 8 + SB 2.7 | Java 17 + SB 3.3 | 当前主流 LTS |
