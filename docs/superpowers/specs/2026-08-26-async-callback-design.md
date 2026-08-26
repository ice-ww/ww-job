# ww-job Phase 2 · 异步回调模型设计

> 日期：2026-08-26
> 状态：已与用户逐节确认
> 背景：解决同步请求-响应模型的固有边界（执行 >10s 的任务跨超时窗口重叠挡不住、重试重复执行、admin 线程被慢任务占用）。

---

## 1. 现状与问题

当前触发链路是**同步请求-响应**：admin 阻塞等待执行器 `/run` 返回，`RestTemplate` 读超时 10s。

三个固有缺陷（思路一只是缓解，没有根治）：
1. **>10s 任务的重叠挡不住**：SINGLE 互斥位在 10s 超时即释放，执行器还在跑时下一次已放行。
2. **超时 = 结果未知**：admin 等到超时就放弃，日志只能记 status=3，需要人工结合执行器日志确认。
3. **admin 线程被占用**：每次触发最多阻塞 10s，慢任务拖累调度吞吐。

**目标**：改为异步回调模型——admin 只负责「投递 + 收账」，执行器异步执行并回调结果，把「执行时间」从 admin 的等待里彻底摘除。

---

## 2. 关键决策记录

| # | 决策 | 结论 |
| --- | --- | --- |
| D1 | 回调永不来的兜底 | **定时巡检**：admin 周期扫 status=0 且超阈值的日志 → 标记 status=3 未知（阈值 = 任务 `timeout`，0 → 默认 60s） |
| D2 | 迟到的回调怎么处理 | **最终一致，覆盖更新**：回调到达即以真实结果覆盖（哪怕当前已是 status=3）；按 logId 幂等 |
| D3 | SINGLE 互斥位放哪 | **DB status=0 计数**（不放在内存 Set）：`SELECT job_info FOR UPDATE` 行锁串行化同一任务的触发决策，再 `count(status=0)` 判断是否在跑 |
| D4 | retryCount 语义 | **只管投递**：仅作用于 `/run` 的投递阶段（连接被拒 / ack 失败 / 繁忙被拒）；ack 收到后执行异步进行，绝不因执行结果重试 |
| D5 | 执行器线程池 | **有界队列 + 快速失败**：`ArrayBlockingQueue` + `AbortPolicy`，满则 `/run` 立即返回「执行器繁忙」，admin 视为明确失败可换机重投 |
| D6 | 回调可靠性 | 执行器回调失败**退避重试 3 次**（0/2/5s），全失败打 warn 交给 admin 巡检兜底 |

---

## 3. 目标架构与数据流

```
admin ──/run(TriggerParam)──► executor ── 立即返回 ack「已受理」──► admin 落日志(status=0)
                                   │
                                   │  executor 线程池异步跑 handler（任意时长，不占用 admin）
                                   │
admin ◄──/callback(CallbackParam)──┘ 执行完主动回调
admin 更新日志为真实结果(status=1/2) + 互斥位自然释放（status 离开 0）
```

**互斥位 = status=0 日志的生命周期**：回调更新为 1/2 → 释放；巡检更新为 3 → 释放；投递失败立即置 2 → 释放。无额外内存结构。

---

## 4. 协议变更（core 模块）

新增 `CallbackParam`（`com.wwjob.core.model`）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| logId | long | 对账锚点，来自 TriggerParam |
| handleCode | int | 200 成功 / 500 失败（ReturnT 语义） |
| handleMsg | String | 结果/错误信息 |
| handleTime | long | 完成毫秒时间戳 |

`TriggerParam` / `JobContext` / `ReturnT` 均不改动。executor → admin 新增请求：`POST /callback`（body = CallbackParam）。

---

## 5. 模块改动明细

### core
- 新增 `CallbackParam`（纯 DTO）。

### executor
- **`JobController.run()` 改异步**：校验 handler → 组装 `JobRunner` 任务丢进线程池 → 立即返回 ack；捕获 `RejectedExecutionException` → 返回 `ReturnT.fail("执行器繁忙，请稍后")`。
- **新增线程池**（`ExecutorAutoConfiguration`）：`ww-job-executor-runner`，core=CPU / max=CPU×2，`ArrayBlockingQueue<>(100)`，`AbortPolicy`，daemon 线程，可配 `wwjob.executor.thread-pool-*`。
- **新增 `JobRunner`**：`handler.execute(ctx)` → 构造 `CallbackParam` → 交给上报器；handler 抛异常 → handleCode=500。
- **新增 `CallbackReporter`**：独立 RestTemplate（connect 3s / read 10s）；向 `props.getAdminAddresses().split(",")` 各 POST `/callback`，失败退避重试 3 次（0/2/5s），全失败 warn。

### admin
- **新增 `POST /callback` 端点**：`selectById(logId)` → 不存在忽略；存在则更新 status/handleCode/handleMsg/handleTime。即使当前 status=3 也覆盖（D2）。重复回调按 logId 幂等覆盖。
- **重构 `JobTriggerServiceImpl.trigger()`**：
  - 事务决策：`SELECT * FROM job_info WHERE id=? FOR UPDATE` → `count(status=0)` → SINGLE 且在跑 → 插 status=3 被阻塞日志返回；否则插 status=0 日志返回 logId。**锁内不发 HTTP**。
  - 锁外投递：POST /run 等 ack（read timeout 调小到 5s）；失败 → 按 `retryCount` 换机重试投递；全部失败 → 立即更新 status=2（明确失败，释放互斥位）。
  - 删除旧「等结果」循环与 10s 读超时等待逻辑。
- **新增 `JobLogTimeoutScanner`**（@Scheduled，每 30s）：查 status=0 且超阈值日志（`job_info.timeout` 为 0 则默认 60s，需 join 任务表）→ 更新 status=3「执行超时未收到回调，结果未知」。
- `ScheduleHelper` 不动；`advanceNextTime` 仍在触发后 finally（ack 瞬时，无影响）。

### schema
- **不加列**。`job_log.status`(0/1/2/3)、handle_* 现成；`job_info.timeout` 终于被读取使用。

---

## 6. 边界（明确记录）

1. **回调重试 3 次全失败** → 真实结果丢失，admin 巡检兜底标 status=3。不做执行器本地持久化待上报（非目标）。
2. **任务实际耗时 > timeout** → 巡检在 timeout 时标未知并释放互斥位，下一 tick 可能再次派发造成重叠；日志以最终回调为准。timeout 配得比任务耗时长即可避免。与 xxl-job「超时自动重触发」行为一致。
3. **多 admin 集群**：DB status=0 计数天然支持互斥（位在库里），但 callback 路由到哪个 admin、FOR UPDATE 跨机竞态是 Phase 3 范围；本次单机部署。
4. **幂等仍然要**：at-least-once 本质不变，重复窗口从「10s 超时」缩到「投递阶段」；业务 handler 仍需以 logId 去重（文档已有此约定）。

---

## 7. 验证方案（端到端实测）

| 场景 | 期望 |
| --- | --- |
| demoHandler 快任务，0/5 | 全 status=1，回调正常 |
| slowHandler 15s + timeout 默认(60s)，0/5 | **全 status=1，无 status=3 超时** → 证明「>10s 任务被打断」解决 |
| slowHandler 15s + SINGLE + 0/5 | 每 15s 派发一次，中间 tick 记 status=3「被阻塞」→ 互斥到回调才释放 |
| slowHandler + timeout=5s | 5s 巡检标 status=3，15s 回调来覆盖成 status=1 → 最终一致 |
| 跑一半 kill 执行器 | 无回调 → 巡检标 status=3 → 互斥释放 → 恢复后能再派发 → 兜底不堵死 |
| 并发狂刷触发 | 部分「执行器繁忙」→ admin 视为明确失败重投递或落 status=2 |
| 手动 + cron 并发 | FOR UPDATE 串行化，不重叠 |

---

## 8. 非目标（本次不做）

- 多 admin 集群 / 分布式锁
- 执行器本地持久化待上报（回调可靠性到「重试 3 次」为止）
- 失败报警、分片广播
- 前端控制台
