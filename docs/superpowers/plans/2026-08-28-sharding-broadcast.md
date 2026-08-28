# 分片广播（Sharding Broadcast）实现计划

> **For agentic workers:** 本计划由用户在 Claude 指导下自行实现（每任务由用户写代码，Claude 逐任务 review，不代写）。步骤用 checkbox（`- [ ]`）跟踪。

**Goal:** 新增 `routeStrategy="sharding"` 路由策略——一次触发向所有在线执行器各派发一个分片，每台并行处理自己的那部分数据。

**Architecture:** admin 侧 `JobTriggerServiceImpl` 增加广播分支：取全部在线地址 → 每台各插一条带 `shard_index` 的 job_log → 各投递一次（复用抽取出的 `dispatchOne`）。core/executor 零改动（Phase 2 已预留 shard 字段）。

**Tech Stack:** Java 17 + Spring Boot 3.3 + MyBatis-Plus + MySQL 8（既有架构）。

**Spec:** `docs/superpowers/specs/2026-08-28-sharding-broadcast-design.md`

## Global Constraints

- 广播路径**不经过 `decide()`**（跳过 SINGLE 互斥与 FOR UPDATE 行锁）
- 广播**不做 retryCount** 重试（某台失败落 status=2，不影响他台）
- 每台执行器各建一条 job_log，独立 status/回调（**绝不共享一条**）
- `job_log` 加 `shard_index INT NOT NULL DEFAULT 0` 列
- `Router` 接口**不动**；复用 `ExecutorRouterService.onlineAddresses()`
- `dispatchOne(log, address, shardTotal)` 抽取；**单台路径调用时 `shardTotal=0`**（保持 shard=0/0 现状）
- executor 模块**零改动**

---

### Task 1: schema + JobLog 实体加 shardIndex

**Files:**
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/entity/JobLog.java`
- Modify: 建表 SQL（`docs/` 下或 sql 目录，找到 job_log 建表语句加列）

**Interfaces:**
- Produces: `JobLog.shardIndex` 字段（Integer）+ getter/setter/构造器

- [ ] **Step 1: 建表 SQL 加列**
  `job_log` 加 `shard_index INT NOT NULL DEFAULT 0`（在 status 列附近）。已有库需 `ALTER TABLE job_log ADD COLUMN shard_index INT NOT NULL DEFAULT 0;`（DataGrip 执行）。
- [ ] **Step 2: JobLog 实体加字段**
  加 `private Integer shardIndex;` + getter/setter/构造器（照现有字段模式）。
- [ ] **Step 3: 编译验证**
  `mvn -pl ww-job-admin -am compile` 通过。
- [ ] **Step 4: Commit**
  `git commit -m "feat: job_log 加 shard_index 列 + JobLog 实体字段（分片广播前置）"`

---

### Task 2: JobTriggerServiceImpl 改造（dispatchOne 抽取 + broadcast）

**Files:**
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/service/JobTriggerServiceImpl.java`

**Interfaces:**
- Consumes: `ExecutorRouterService.onlineAddresses(long jobGroupId)`、`JobLog.shardIndex`
- Produces: `broadcast(JobInfo, String)`、`dispatchOne(JobLog, String, int)`；`trigger()` 加广播分支

- [ ] **Step 1: 抽取 `dispatchOne(JobLog log, String address, int shardTotal)`**
  把现有 `dispatch()` 的「单次投递」核心搬进去：
  - 构造 `TriggerParam`：jobId / handler / executorParam / logId / **shardIndex=log.getShardIndex()** / **shardTotal=传入参数**
  - POST /run：ack 成功 → 日志保持 status=0（等回调）、`job.setTriggerLastTime` + updateById，return
  - 投递异常（连接拒绝等，非超时）→ 抛给外层决定（单台重试/广播落 2）
- [ ] **Step 2: `trigger()` 加分支**
  ```
  if ("sharding".equalsIgnoreCase(job.getRouteStrategy())) { broadcast(job, type); return; }
  // 否则：现有 decide() + dispatch()
  ```
- [ ] **Step 3: 新增 `broadcast(JobInfo job, String triggerType)`**
  ```
  addresses = executorRouterService.onlineAddresses(job.getJobGroupId());
  if (addresses.isEmpty()) → 插一条 status=2「无可用执行器」日志，return
  total = addresses.size();
  for i in 0..total-1:
      log = 插日志(status=0, shardIndex=i, handleMsg="已受理，等待执行结果")
      try:
          dispatchOne(log, addresses.get(i), total)   // 一次投递，无重试
      catch (投递异常):
          log.setStatus(2) + updateById(log)          // 该台落 2，不影响他台
  ```
  广播插日志：直接 `jobLogMapper.insert`（不经 decide，无事务需求）。
- [ ] **Step 4: 现有 `dispatch()` 改用 dispatchOne + retryCount 循环**
  单台路径行为不变：route 选地址 → dispatchOne(log, address, 0)；失败按 retryCount 换地址重投；超时 break。
- [ ] **Step 5: 编译**
  `mvn -pl ww-job-admin -am compile` 通过。
- [ ] **Step 6: Commit**
  `git commit -m "feat: 分片广播路由（broadcast 每台独立日志投递 + dispatchOne 抽取）"`

---

### Task 3: 新增 ShardingDemoHandler

**Files:**
- Create: `ww-job-executor-samples/src/main/java/com/wwjob/executor/samples/handler/ShardingDemoHandler.java`

**Interfaces:**
- Produces: `@JobHandler("shardingDemoHandler")`

- [ ] **Step 1: 写 handler**
  `@JobHandler("shardingDemoHandler")`，`execute(JobContext ctx)`：
  - 打印 `分片 shard=shardIndex/total，处理 id % total == shardIndex 的那份数据`
  - `Thread.sleep(3000)`（便于观察并发）
  - 返回 `ReturnT.success(...)`
- [ ] **Step 2: 编译**
  `mvn -pl ww-job-executor-samples -am compile` 通过。
- [ ] **Step 3: Commit**
  `git commit -m "feat: shardingDemoHandler 示例（按 shardIndex 处理自己那份数据）"`

---

### Task 4: 端到端验证（2 台 executor）

**Files:**
- 不改代码；起服务、建任务、查库

- [ ] **Step 1: 起 2 台 executor**
  同 jobGroup 注册两个地址：默认 `localhost:8082`，再开一台改端口（如 `8083`）+ 注册地址。确认 admin 注册中心看到 2 个在线地址。
- [ ] **Step 2: 建任务**
  `routeStrategy="sharding"` + `handlerName="shardingDemoHandler"`，cron 停用（手动触发验证）。
- [ ] **Step 3: 手动触发，验证**
  - `job_log` 出现 **2 条**日志，`shard_index`=0 和 1
  - 两台 executor 控制台各打印 `shard=0/2`、`shard=1/2`
  - 两条最终各自 status=1（独立回调）
- [ ] **Step 4: 边界验证**
  - 停掉一台 executor 再触发 → 只剩 1 条日志，shard=0/1
  - 全停 → 落 status=2「无可用执行器」
- [ ] **Step 5: 回归**
  单台任务（routeStrategy 默认）手动触发 → 仍 shard=0/0、行为不变（验证 dispatchOne 抽取没破坏单台）

---

### Task 5: 文档标记已实现 + 收尾

**Files:**
- Modify: `docs/superpowers/specs/2026-08-28-sharding-broadcast-design.md`

- [ ] **Step 1: spec 标记状态** → 已实现并端到端验证，验证表标 ✅
- [ ] **Step 2: Commit**
  `git commit -m "feat: 分片广播端到端验证通过 + 文档标记已实现"`
- [ ] **Step 3: 合并 main + 推送**（照 Phase 2 流程）
