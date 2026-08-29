# ww-job

对标 **xxl-job** 的轻量级分布式任务调度平台（Phase 1 精简实现），个人后端实习项目。

调度中心（admin）与执行器（executor）分离，通过 HTTP 请求-响应通信；调度器与注册中心均为自研实现，
不依赖 Quartz、Zookeeper、Nacos 等外部组件。

## 功能特性

- **调度中心 + 执行器分离**，HTTP 通信，执行器可独立部署、横向扩容
- **自研注册中心**：执行器启动自动注册，每 30s 心跳保活，admin 每 10s 剔除 90s 无心跳的节点（自动上下线）
- **自研时间轮调度器**：DB 预读 + 时间轮精确触发，每秒一个 tick，无 Quartz
- **cron 触发**：cron 表达式解析下次触发时间，触发后自动推进，任务不停机
- **手动触发**：`triggerType` 区分 `cron` / `manual`
- **失败自动重试**：可配置 `retryCount` 次重试，重试可能换执行器
- **路由策略**：轮询（round）/ 随机（random）/ 故障转移（failover）
- **执行日志**：每笔触发落库，支持按任务 / 状态分页查询
- **任务分组**：按 appName 分组管理执行器与任务

## 架构

```
┌────────────────────────── ww-job-admin :8080 ──────────────────────────┐
│                                                                         │
│  scheduleThread(每1s 扫DB预读5s窗口) ──► 时间轮 TimeWheel ──► 到期触发      │
│                                                 │                       │
│                                                 ▼                       │
│                                     ExecutorRouter 选一台在线执行器        │
│                                            │                            │
│                                            │ POST /run                  │
│            ┌───────────────────────────────┼────────────────────────────┼─────────────┐
│            │                               ▼                            │             │
│            │                    ww-job-executor-samples :8081           │             │
│            │                        @JobHandler("demoHandler")          │             │
│            │                             执行并返回                      │             │
│            │                                                           │             │
│  RegistryCleaner(每10s 剔除90s无心跳)       注册/心跳                      │             │
│            ◄────────────────────────── POST /registry /heartbeat ──────►  │             │
└───────────────────────────────────────────────────────────────────────────┘
```

**调度链路**

1. 执行器启动后向 admin 注册 `(appName, IP:port)`，之后每 30s 心跳；admin 每 10s 清理 90s 无心跳的节点
2. admin 的 `scheduleThread` 每秒扫描 DB，把未来 5s 内到期的启用任务放入时间轮（预读）
3. `ringThread` 每秒推进时间轮，到期的任务触发：按路由策略从该分组的在线执行器里选一台
4. admin 携带 `TriggerParam`（jobId / handler / param / logId）HTTP POST 到执行器 `/run`
5. 执行器通过 `@JobHandler` 注册表找到处理器执行，返回 `ReturnT`
6. admin 依据执行结果落日志、记 `triggerLastTime`，并推进下次 `triggerNextTime`

## 技术栈

| 技术 | 版本 | 用途 |
| --- | --- | --- |
| Java | 17 | 语言 |
| Spring Boot | 3.3.5 | 应用框架 |
| Maven | 多模块 | 工程管理 |
| MySQL | 8 | 数据存储（4 张表，schema.sql 自动初始化） |
| MyBatis-Plus | 3.5.7 | ORM |
| Redis | 7 | 演示/备用（docker-compose 提供） |
| 自研时间轮 / 注册中心 | - | 调度与执行器发现 |

## 模块说明

| 模块 | 说明 |
| --- | --- |
| **ww-job-core** | 共享内核：`ReturnT` 统一返回、`TriggerParam`/`RegistryParam` 通信 DTO、`IJobHandler` + `@JobHandler` 注解、时间轮 `TimeWheel`、`CronUtil`（cron 下次触发时间）、路由策略（轮询/随机/故障转移） |
| **ww-job-executor** | 执行器库：自动配置（配置了 `wwjob.executor.app-name` 即自动启用）、注册中心客户端（注册 + 心跳）、`JobHandlerRegistry` 处理器注册表、`/run` 任务执行入口。业务方引入依赖并实现 `IJobHandler` 即可接入 |
| **ww-job-admin** | 调度中心：REST API（任务 / 分组 / 日志 / 注册中心）、DB 预读调度线程 + 时间轮、触发分发（路由 + 重试）、执行日志、下线清理 |
| **ww-job-executor-samples** | 示例执行器：`appName = sample-executor`，内含 `demoHandler` 示例 |

## 快速开始

前置：JDK 17、Maven 3.8+，Docker（可选，用于一键起 MySQL）。

### 1. 启动 MySQL（含 Redis）

```bash
docker compose up -d        # MySQL 8（root/root，库 ww_job）+ Redis 7
```

admin 启动时会自动执行 `schema.sql` 建表（job_group / job_info / job_registry / job_log）。

### 2. 配置数据库连接

仓库内默认 `application.yml` 使用 `root/root` 连接本地 MySQL。若本机密码不同，在 admin 的
`src/main/resources/` 下新建 **`application-local.yml`**（已被 .gitignore 忽略，不会提交）覆盖：

```yaml
spring:
  datasource:
    username: root
    password: 你的密码
```

### 3. 启动调度中心（admin，端口 8080）

```bash
mvn -pl ww-job-admin -am spring-boot:run -Dspring-boot.run.profiles=local
```

> 用了 local profile 才会读取 `application-local.yml`；不需要自定义连接时可省略该参数。

### 4. 启动示例执行器（samples，端口 8081）

```bash
mvn -pl ww-job-executor-samples -am spring-boot:run
```

执行器启动后自动向 admin 注册并开始心跳，无需手动配置。

### 5. 验证

```bash
# 查看执行器分组（应能看到 sample-executor）
curl http://localhost:8080/jobgroup/list

# 创建任务：cron 每 5 秒触发，handler 指向 demoHandler，blockStrategy=SINGLE（重叠丢弃）
curl -X POST http://localhost:8080/job \
  -H "Content-Type: application/json" \
  -d '{"jobGroupId":1,"handlerName":"demoHandler","cron":"0/5 * * * * ?","routeStrategy":"round","retryCount":0,"blockStrategy":"SINGLE","triggerStatus":1}'

# 查看执行日志（应每 5 秒新增一条成功记录）
curl "http://localhost:8080/joblog/page?size=5"

# 手动触发一次（triggerType=manual）
curl -X POST http://localhost:8080/job/1/trigger

# 停止 / 启动任务
curl -X POST http://localhost:8080/job/1/stop
curl -X POST http://localhost:8080/job/1/start
```

## REST API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/jobgroup` | 创建执行器分组 |
| GET | `/jobgroup/list` | 分组列表 |
| POST | `/job` | 创建任务 |
| PUT | `/job` | 更新任务（重置 next trigger time） |
| GET | `/job/page?page=&size=` | 任务分页 |
| POST | `/job/{id}/trigger` | 手动触发（triggerType=manual） |
| POST | `/job/{id}/start` | 启动任务 |
| POST | `/job/{id}/stop` | 停止任务 |
| GET | `/joblog/page?page=&size=&jobId=&status=` | 执行日志分页 |
| GET | `/joblog/{id}` | 日志详情 |
| GET | `/dashboard/stats` | 概览统计（任务/执行器/今日日志/失败 TOP） |
| POST | `/registry` | 执行器注册 |
| GET | `/registry/list` | 在线执行器列表 |
| POST | `/heartbeat` | 执行器心跳 |
| POST | `/run` | 执行器执行任务入口（admin → executor） |

## 数据模型

| 表 | 说明 | 关键字段 |
| --- | --- | --- |
| `job_group` | 执行器分组 | app_name、title、address_type、address_list |
| `job_info` | 任务 | handler_name、cron、route_strategy、block_strategy、retry_count、trigger_status、trigger_next_time、trigger_last_time |
| `job_registry` | 在线执行器 | job_group_id、registry_value(IP:port)、heartbeat_time |
| `job_log` | 执行日志 | job_id、executor_address、handler_name、trigger_type、trigger_time、handle_time、handle_code、handle_msg、status |

## 目录结构

```
ww-job/
├── ww-job-core/            # 共享内核
├── ww-job-executor/        # 执行器库
├── ww-job-admin/           # 调度中心
├── ww-job-executor-samples/# 示例执行器
├── docs/                   # 设计与计划文档
└── docker-compose.yml      # MySQL + Redis
```

## 执行语义与注意事项

当前采用**同步请求-响应**分发模型，几个关键语义务必了解，业务方才能正确使用：

1. **At-least-once（至少一次）**：调度与分发不保证"恰好一次"。`handler` 必须幂等——用 `logId`（`JobContext.getLogId()`）作为去重键，重复执行时对同一 `logId` 只做一次副作用（扣款 / 发短信等）。
2. **超时 ≠ 失败（status=3 未知）**：admin 等待执行器返回最多 10s。超时后结果不确定（执行器可能仍在执行），日志记为 **status=3（未知）**，并且**不会自动重试**——重试会让同一任务并行跑多遍，对非幂等 handler 是事故。看到 status=3 请结合执行器日志确认实际结果，勿重复手动触发。
3. **重试只发生在"明确失败"**：只有连接被拒绝（handler 未启动）或执行器明确返回失败码才重试。`retryCount` 次重试可能换执行器（failover / 轮询）。
4. **阻塞策略 `blockStrategy=SINGLE`（默认 `serial`，不互斥）**：同一任务同一时刻只允许一个实例在跑。cron 间隔小于执行耗时、上一次还没结束时，本次触发直接丢弃并记一条 status=3（被阻塞）日志。⚠️ **局限**：互斥是 admin 进程内的，且**执行超过 10s 的任务在超时释放后无法被完全挡住重叠**（handler 仍在跑，下一次已放行）——这是同步模型的固有边界，Phase 2 将改为异步回调模型根治。
5. **手动触发与 cron 触发共用同一互斥判断**（`SINGLE` 下两者互斥）；但不同任务之间不互斥，handler 需自行保证线程安全。

## Phase 1 范围与后续规划

- [x] 注册中心（注册 / 心跳 / 下线清理）
- [x] 时间轮调度（DB 预读 + 精确触发）
- [x] HTTP 任务分发与执行
- [x] 失败重试 / 手动触发 / 任务与分组管理
- [x] 执行日志
- [x] 阻塞策略（SINGLE 互斥）/ 超时控制（超时不重试 + status=3 未知态）
- [x] 分片广播
- [ ] 调度中心集群（分布式锁）
- [x] 前端控制台（`ww-job-web`，Vue3 + Element Plus，概览仪表盘 / 任务管理 / 执行日志 / 执行器在线列表 + Cron 可视化配置）

---

作者：王威（ice-ww）· 后端实习项目
