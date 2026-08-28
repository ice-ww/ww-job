# ww-job 分片广播（Sharding Broadcast）设计

> 日期：2026-08-28
> 状态：已实现并端到端验证通过（2 台 executor 实测）
> 背景：单个慢任务串行跑完整体数据耗时不可接受；需要把一个任务按执行器数量切片，并行处理。

---

## 1. 现状与问题

当前触发链：一次触发 → `decide()`（事务决策 + 单台路由）→ `dispatch()` 向**一台**执行器投递 → 该台异步执行 → 回调。

**局限**：任务始终只在一台执行器上跑。当任务要处理的数据量很大（如 10 万订单），单台执行器串行处理，耗时 = 总量 / 单台吞吐。没有利用多执行器并行。

**目标**：新增**分片广播**路由策略——一次触发，向所有在线执行器各派发一个分片（shard），每台只处理自己的那部分数据，总耗时 = 总量 / 执行器数。

---

## 2. 关键决策记录

| # | 决策 | 结论 |
| --- | --- | --- |
| D1 | 分片广播的日志模型 | **每台执行器各建一条 job_log**，每条独立 status / handle_msg / handle_time / 回调对账。绝不共享一条日志（多台回调写同一条会互相覆盖、丢失其他台结果） |
| D2 | 与 SINGLE 互斥的关系 | **广播不参与 SINGLE 互斥**。SINGLE 保证「同一 job 不被重复执行」，广播是「一次触发派发给多实例」——两者管不同维度。广播路径**不经过 `decide()`**（跳过 FOR UPDATE 行锁与 count 检查） |
| D3 | 广播下的 retryCount | **不做投递重试**。某台投递失败直接落 status=2，不影响其他台。理由：广播是 N 台并行，重试某台需重新决策分片数、语义混乱；与 xxl-job 一致 |
| D4 | Router 接口是否改 | **不改**。分片广播「取全部地址」而非「从列表选一个」，超出 `Router` 职责。复用 `ExecutorRouterService.onlineAddresses()` 即可 |
| D5 | job_log 是否记录 shard | **加 `shard_index` 列**。分片是核心特性，日志不带 shard 无法区分每条属于哪个分片，验证与面试演示都需要直观 |

---

## 3. 目标架构与数据流

```
一次 cron 触发（routeStrategy=sharding）
     │
     ▼
admin：取全部在线执行器（假设 3 台 A、B、C），total=3
     │
     ├─► job_log#1  shard_index=0  ──► /run A（TriggerParam shard=0/3）──► ack ──► 异步执行 ──► 回调#1
     ├─► job_log#2  shard_index=1  ──► /run B（TriggerParam shard=1/3）──► ack ──► 异步执行 ──► 回调#2
     └─► job_log#3  shard_index=2  ──► /run C（TriggerParam shard=2/3）──► ack ──► 异步执行 ──► 回调#3
```

- 每台一条日志、一个 `TriggerParam`，各自带 `shardIndex` / `shardTotal`（=`shard_index` / 在线数）
- 各台独立走「ack + 回调」，互不干扰
- **数据怎么切是业务 handler 的责任**：handler 拿 `shardIndex/shardTotal` 自行决定处理哪些数据（如 `id % total == shardIndex`）

---

## 4. 改动明细

### schema
- `job_log` 加列：`shard_index INT NOT NULL DEFAULT 0`（单台任务 = 0；分片任务 = 该台在列表中的下标）
- `JobLog` 实体加字段 `shardIndex`（含 getter/setter/构造器）

### core
- **无改动**。`TriggerParam` / `JobContext` 的 `shardIndex/shardTotal` 字段 Phase 2 已预留；`Router` 接口不动（见 D4）

### admin

**`JobTriggerServiceImpl`**（主要改动）：
- `trigger()` 加分支：`"sharding".equalsIgnoreCase(job.getRouteStrategy())` → 走 `broadcast(job, type)`；否则走现有 `decide() + dispatch()` 单台路径
- 新增 `broadcast(JobInfo job, String triggerType)`：
  1. `addresses = executorRouterService.onlineAddresses(job.getJobGroupId())`
  2. 空 → 插一条 `status=2`「无可用执行器」日志，返回
  3. `total = addresses.size()`；for i in 0..total-1：
     - 插日志：`status=0`、「已受理，等待执行结果」、`shardIndex=i`
     - `dispatchOne(log, addresses.get(i), total)` —— **一次投递，无 retryCount**；`TriggerParam` 带 `shardIndex=log.getShardIndex()`、`shardTotal=total`
- 抽取 `dispatchOne(JobLog log, String address, int shardTotal)`：现有 `dispatch()` 的「单次投递」核心——构造 `TriggerParam`（`shardIndex` 取 `log.getShardIndex()`、`shardTotal` 由调用方传入）→ POST /run → ack 成功保持 status=0 / 失败置 status=2。单台与广播共用，差异仅外层是否有重试循环。**单台路径调用时传 `shardTotal=0`**（保持现状 shard=0/0）

**`ExecutorRouterService`**：
- 复用现有 `onlineAddresses(jobGroupId)`（返回 `List<String>` 全部在线地址）。不新增 Router 实现

**`JobDecisionService`**：
- **不动**。广播不经过它；单台 `decide()` 原样保留

### executor
- **零改动**。`JobContext` 已带 shard，`JobRunner` 已透传，回调 `handle_msg` 带 handler 打印的 shard

### samples
- 新增 `ShardingDemoHandler`（`@JobHandler("shardingDemoHandler")`）：`execute` 里打印 `shard=shardIndex/total`，模拟「只处理分给我的那份」（如 `处理 订单id % total == shardIndex 的批次`），可 sleep 几秒便于观察并发

---

## 5. 边界（明确记录）

1. **无在线执行器** → 插一条 status=2「无可用执行器」，广播自然结束
2. **某台投递失败**（连接拒绝/超时）→ 该台日志 status=2，**不重试**（D3），不影响其他台
3. **某台执行器掉线**（注册过期）→ 它不在 `onlineAddresses` 里，不参与本次广播；分片数 = 当时在线数，数据切分随之变化（handler 按 total 自适应）
4. **SINGLE 与广播混配** → 以广播为准，忽略 SINGLE 语义（D2）
5. **分片数据如何切** → 业务 handler 责任，admin 只传 shardIndex/shardTotal
6. **回调可靠性** → 沿用 Phase 2 异步模型：ack + 回调 + 巡检兜底（status=0 超阈值→3）不变

---

## 6. 验证方案（端到端实测）

前置：同一 jobGroup 注册 **2 台 executor**（改 executor 配置端口/地址，或开两个实例）。

| 场景 | 实测结果 |
| --- | --- |
| 建 `routeStrategy="sharding"` + `shardingDemoHandler` 任务，手动触发一次（2 台在线） | ✅ `job_log` 出现 **2 条**日志，`shard_index` = 0 和 1，各台独立回调，最终各自 status=1 |
| 看两台 executor 控制台 | ✅ 各打印自己的 `shard=0/2`、`shard=1/2`，且同时执行（并发） |
| 停掉其中一台 executor 再触发（等注册 90s 过期后） | ✅ 只剩在线那台的 1 条日志，shard=0/1 |
| 全停 executor 再触发 | ✅ 1 条日志 status=2「无可用执行器」 |
| 手动触发的同时看互斥 | ✅ 配 `blockStrategy=SINGLE` 的 sharding 任务连发两次（2 台×2 次），4 条日志全正常执行，无「被阻塞丢弃」——广播绕过 SINGLE |
| 回归：单台任务（routeStrategy 默认） | ✅ 1 条日志 shard_index=0，控制台 `shard=0/0`，行为不变 |

---

## 7. 非目标（本次不做）

- 分片数据分布策略（如按 id 取模、按范围）——业务 handler 自行决定
- 广播任务的失败聚合/报警（某台失败只落该台日志）
- 动态分片数的负载均衡（固定 = 在线执行器数）
- 前端控制台展示分片
