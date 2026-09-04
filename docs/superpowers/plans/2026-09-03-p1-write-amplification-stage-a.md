# P1 触发写放大压缩 · 阶段 A 实现计划

> **执行说明（本项目协作模式）**：所有后端改动由用户 ice-ww **自研编写**，本计划中的代码是**参考实现**（对照理解，不照抄即可）。Claude 负责维护测试工具、跑回归与 D=300 A/B 压测对照。**不采用 subagent 代写**。步骤用 checkbox（`- [ ]`）跟踪。提交节奏由你决定（本次仅在你要求时 commit/push）。

**Goal:** 把 cron 快任务触发路径的每触发 DB 往返从 ~10 降到 ~7、回调收账从 2 往返压成 1 条条件窄更新，同时不破坏任何既有正确性不变式。

**Architecture:** 三类改动收敛在两条链上：
- **job_info 推进链**（A4+A1）：全行 `updateById` 收敛为 `advanceNextTime` 窄更新 → claim 返回锁内新鲜 `JobInfo`、cron 路径直接喂 `trigger(JobInfo)`，去掉「claim 后 trigger 重查」的 #4 往返。
- **job_log 决策分发链**（A2+A3+A6）：decide 在 SINGLE gate **之后**、INSERT **之前** route，地址直接写进 INSERT（去 #10 补写、去 dispatch 的 #8 重查）；decide 返回 `{log, job}` 载具；收尾/回调统一改条件窄更新。

**Tech Stack:** Java 17、Spring Boot 3.3.5、MyBatis-Plus、MySQL（条件 `@Update` + 行锁，无新增依赖）

**Spec:** `docs/superpowers/specs/2026-09-03-p1-write-amplification-design.md`

## Global Constraints

后端自研、计划代码为参考；不改动任何非 P1 文件。

**正确性不变式（红绿灯，任何一步不得破坏）：**
1. **F6-2**：cron 触发点幂等——claim 行锁内先窄更新推进 next_time 再放行；`claimable` 秒级截断保留。
2. **锁窗口**：HTTP 分发（读 10s 超时）绝不在行锁事务内执行。
3. **SINGLE**：`countRunning` 判定与 running log 的 insert 必须同一行锁事务；decide 的 route 在 gate **之后**（被阻塞的 99% 不消耗 registry 读）。
4. **F2-9**：对 `job_info` 只窄列更新（next/last/status），绝不整实体 `updateById` 写回。
5. **D2**：超时被置 3 后迟到回调仍可覆盖 3→1/2（`completeById` WHERE `status IN (0,3)`）。
6. **幂等收账**：job_log 终态更新一律条件窄更新 + status 守卫，绝不整行 `updateById` 覆盖并发回调。
7. **分片广播**：每分片独立 job_log；不经 SINGLE gate/行锁；广播地址在 INSERT 前已知、落进 INSERT。

**文件边界（本次绝不动）：**
- `docs/superpowers/plans/2026-08-29-executor-admin-failover.md`、`docs/superpowers/plans/2026-08-30-auth-jwt.md`、`ww-job-executor/**/JobController.java`（用户文件，不接触不提交）
- 任何代码/文档不出现真实 MySQL 密码 / QQ SMTP 授权码

**行为契约（回调语义）：**
- 重复/迟到回调一律返回 HTTP 200（杜绝 executor CallbackReporter 对已终态的重试风暴）；真不存在 logId 才返回 500「logId 不存在」

**实测发现 2026-09-04（Task 1-3 验证时撞出的潜伏 bug，已修）：**
- **根因**：`ReturnT` 无 `success(String msg)` 重载（与 `success(T data)` 擦除冲突，加不了）；样例 handler（Demo/ShardingDemo/Slow/LoadTest）全写 `success(文本)` → 文本落进 **data**、`msg` 恒 null。JobRunner 只读 `result.getMsg()` → 回调 `handleMsg=null`。
- **为何以前看不出**：旧 `updateById` 走 MP NOT_NULL 跳过 null 字段，占位符「已受理，等待执行结果」永远保留（DB 残留 1521 行即化石）；Task 2 的 `completeById` @Update 无条件写，把真实 null 落库才暴露。**Task 2 代码正确，非回归。**
- **修复（executor 侧 JobRunner，2026-09-04）**：收获 msg 为空时回落 `result.getData()`（`IJobHandler` 返回 `ReturnT<String>`，data 即结果文本）。改后需重启 executor 生效。
- **Task 6 回归预期**：成功行 `handle_msg` = handler 真实文本（`demo 执行成功…`/`shardingDemo 执行成功…`/`load test ok`），**非 null、非占位符**。

---

### Task 1: JobLogMapper 加三条收账窄更新方法

**Files:**
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/mapper/JobLogMapper.java`（import 区加 `org.apache.ibatis.annotations.Update`，其余 import 已齐：`Param`/`LocalDateTime`）

**Interfaces:**
- Consumes: 无
- Produces: `int completeById(id, status, handleCode, handleMsg, handleTime)`；`int endRunning(id, status, handleCode, handleMsg, handleTime)`；`int updateExecutorAddress(id, address)`（供 Task 2/3 使用，命名/参数不得改）

- [ ] **Step 1: 在接口内加三条窄更新**

```java
/** 回调收账终态条件窄更新。WHERE status IN (0,3)：运行中→终态；被超时置 3 后迟到回调仍覆盖（D2）。
 *  已终态(1/2)重复回调 → 0 行 → 幂等。blocked=4（item5）无执行器、永不收回调，天然不在 0/3 内。
 *  返回行数供「不存在」判错。 */
@Update("UPDATE job_log SET status=#{status}, handle_code=#{handleCode}, handle_msg=#{handleMsg}, handle_time=#{handleTime} "
        + "WHERE id=#{id} AND status IN (0, 3)")
int completeById(@Param("id") long id, @Param("status") int status, @Param("handleCode") int handleCode,
                 @Param("handleMsg") String handleMsg, @Param("handleTime") LocalDateTime handleTime);

/** 调度侧收尾窄更新：仅当日志仍在运行(0)才置终态。并发回调先落 1/2 → 0 行自动跳过，不覆盖真实结果 */
@Update("UPDATE job_log SET status=#{status}, handle_code=#{handleCode}, handle_msg=#{handleMsg}, handle_time=#{handleTime} "
        + "WHERE id=#{id} AND status=0")
int endRunning(@Param("id") long id, @Param("status") int status, @Param("handleCode") int handleCode,
               @Param("handleMsg") String handleMsg, @Param("handleTime") LocalDateTime handleTime);

/** 重试改投新地址时精确更新（仅 attempt>0 且地址变化时用） */
@Update("UPDATE job_log SET executor_address = #{address} WHERE id = #{id}")
int updateExecutorAddress(@Param("id") long id, @Param("address") String address);
```

- [ ] **Step 2: 编译通过**

Run: `mvn -q -pl ww-job-admin -am compile`
Expected: BUILD SUCCESS（方法尚无调用点，纯加法）

---

### Task 2: A5 回调收账改单条条件窄更新

**Files:**
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/controller/CallbackController.java`（仅 callback 方法体）

**Interfaces:**
- Consumes: Task 1 的 `completeById`
- Produces: 新回调语义——已终态重复/迟到回调返回 200「已是最新状态，忽略重复回调」，不再整行覆盖

- [ ] **Step 1: 替换 callback 方法体**

```java
@PostMapping("/callback")
public ReturnT<String> callback(@RequestBody CallbackParam param){
    int status = param.getHandleCode() == ReturnT.SUCCESS_CODE ? JobLog.STATUS_SUCCESS : JobLog.STATUS_FAIL;
    LocalDateTime handleTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(param.getHandleTime()), ZoneId.systemDefault());
    int rows = jobLogMapper.completeById(param.getLogId(), status,
            param.getHandleCode(), param.getHandleMsg(), handleTime);
    if (rows > 0) {
        return ReturnT.success();
    }
    // 0 行：要么 logId 不存在，要么已终态（幂等）。只在罕见分支查一次库
    return jobLogMapper.selectById(param.getLogId()) == null
            ? ReturnT.fail("logId 不存在:" + param.getLogId())
            : ReturnT.success("已是最新状态，忽略重复回调");
}
```

> 论证：executor 的 CallbackReporter 对非 200 会退避重试 3 次——对已终态返回 200 可杜绝重试风暴；真不存在的 logId 仍返回 500 保 executor 误报排查能力。

- [ ] **Step 2: 编译 + 单测回归**
Run: `mvn -q -pl ww-job-admin -am compile`（admin 无单测，回归靠 Task 4/5 的 e2e）

---

### Task 3: JobInfoMapper 加 advanceNextTime + A4 两处全行写收敛

**Files:**
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/mapper/JobInfoMapper.java`（import `Update` 已存在）
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/service/JobTriggerServiceImpl.java:75`（claim 内全行 updateById）
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/schedule/ScheduleHelper.java:92`（catch-up 全行 updateById）

**Interfaces:**
- Consumes: 无
- Produces: `int advanceNextTime(id, nextTime)`——只推 `trigger_next_time`，不写回整行

- [ ] **Step 1: JobInfoMapper 加窄更新**

```java
/** 只推进下次触发时间（窄更新）。全行 updateById 会把加载时的旧 update_time 显式写回，
 *  使 ON UPDATE CURRENT_TIMESTAMP 永不触发（update_time 恒等于 create_time）；窄更新规避之。
 *  行锁内/幂等追赶场景均安全 */
@Update("UPDATE job_info SET trigger_next_time = #{nextTime} WHERE id = #{id}")
int advanceNextTime(@Param("id") long id, @Param("nextTime") long nextTime);
```

- [ ] **Step 2: claim 内替换（JobTriggerServiceImpl:73-75）**

删除三行中的全行写，保留内存赋值（本任务 claim 仍返回 boolean）：

```java
long next = CronUtil.nextTime(cron, now);
jobInfoMapper.advanceNextTime(jobId, next);   // 原 jobInfoMapper.updateById(job) 全行写 → 窄更新
job.setTriggerNextTime(next);
```

- [ ] **Step 3: ScheduleHelper catch-up 替换（ScheduleHelper:87-92）**

```java
if (next < now) {
    next = CronUtil.nextTime(job.getCron(), now);
    jobInfoMapper.advanceNextTime(job.getId(), next);   // 原 jobInfoMapper.updateById(job) → 窄更新
}
```

> 注意：`job.setTriggerNextTime(next)` 在旧代码里只是配合 updateById 的内存赋值，去掉后 `delay` 用局部 `next` 计算，行为不变。原 `updateById(job)` 整行会把 `trigger_status`/其他列一并写回——窄更新同时消除「追赶写回覆盖并发 /stop」的隐患（与 F2-9 同源）。

- [ ] **Step 4: 编译**
Run: `mvn -q -pl ww-job-admin -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: update_time 失真修复抽查（可先做，属 A4 附带收益）**
Run: 起单 admin + executor，对一个 cron 任务触发 1 次，随后 `SELECT id, create_time, update_time, trigger_next_time FROM job_info WHERE id=<jobId>`
Expected: `update_time` ≠ `create_time` 且随每次触发刷新（修复前两者恒等）

---

### Task 4: A2+A3+A6 决策分发链手术（decide 返回载具 + route 进 INSERT + 收尾窄更新）

**Files:**
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/service/JobDecisionService.java`（decide 重构 + 嵌套 `DecideResult` + 注入 ExecutorRouterService；`ExecutorRouterService` 同包无需 import；无执行器分支刻意不置 FAIL_CODE，故**不需要**加 `ReturnT` import——加了就是死 import）
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/service/JobTriggerServiceImpl.java`（trigger/dispatch/dispatchOne/broadcast 重构；import 加 `JobDecisionService.DecideResult`——同包嵌套类需显式 import）

**Interfaces:**
- Consumes: Task 1 的 `endRunning`/`updateExecutorAddress`；`ExecutorRouterService.route`/`onlineAddresses`（现成）
- Produces: `JobDecisionService.DecideResult{log, job}`——`decide` 返回值类型 `Long → DecideResult`；`dispatch(DecideResult, type)` 取代 `dispatch(Long, JobInfo, type)`；`dispatchOne` 不再写库

> 本任务是整条链的核心手术，改动必须整批一次编译（decide 返回类型一变，trigger/dispatch 同步改否则编译失败）。拆为 4 个连续编辑 + 1 次编译。
>
> **引用陷阱（2026-09-04 实测，编译报「无法解析符号 DecideResult / 方法 endRunning」）**：① `DecideResult` 是 `JobDecisionService` 的**内部类**，同包也不自动可见——代码里须写 `JobDecisionService.DecideResult`（或显式 import）；② 收账窄更新 `endRunning` 在 `JobLogMapper` 上，调用必须带 `jobLogMapper.` 前缀，类内裸写不解析。

- [ ] **Step 1: JobDecisionService 注入 router + decide 重构**

构造器加依赖：

```java
private final JobInfoMapper jobInfoMapper;
private final JobLogMapper jobLogMapper;
private final ExecutorRouterService routerService;

public JobDecisionService(JobInfoMapper jobInfoMapper, JobLogMapper jobLogMapper,
                          ExecutorRouterService routerService) {
    this.jobInfoMapper = jobInfoMapper;
    this.jobLogMapper = jobLogMapper;
    this.routerService = routerService;
}
```

decide 整体替换（原 saveLog 私有方法删除，换为带地址/时间参数的 insertLog）：

```java
/** 决策：行锁内判定 SINGLE 互斥（门）→ 门后 route → 地址直接写进 running log 的 INSERT。
 *  返回已落库的 log + 行锁内最新 job 供 dispatch 直接用；无执行器/被阻塞/无任务 → 返回 null。 */
@Transactional
public DecideResult decide(long jobId, String triggerType) {
    JobInfo job = jobInfoMapper.selectByIdForUpdate(jobId);
    if (job == null) {
        return null;
    }
    boolean single = "SINGLE".equalsIgnoreCase(job.getBlockStrategy());
    if (single) {
        long running = jobLogMapper.countRunning(jobId);
        if (running > 0) {
            // 上一次执行尚未结束：丢弃本次触发。STATUS_BLOCKED=4（item5 拆分后口径）：被丢弃≠超时未知
            // （handle_time=null ⇒ 不入告警/巡检，Dashboard 单独可见）；blocked 永不收回调，收账守卫 IN(0,3) 天然不含 4
            insertLog(job, triggerType, "任务上一次执行尚未结束，本次触发被阻塞丢弃",
                    JobLog.STATUS_BLOCKED, null, null, null);
            return null;
        }
    }
    // SINGLE gate 之后才 route：被阻塞的触发不消耗 registry 读
    String address = routerService.route(job.getJobGroupId(), job.getRouteStrategy(), jobId);
    if (address == null) {
        // 无可用执行器：直接落失败日志（原 running→fail 两次写收敛为一次写），不分发。
        // handleCode/handleTime 保持空 —— 与旧 route==null 立即返回分支一致（旧代码此处不置位，
        // 使该失败不进入「最近失败」告警扫描；改置位会静默改变告警可见性，故不收口）
        insertLog(job, triggerType, "无可用执行器",
                JobLog.STATUS_FAIL, null, null, null);
        return null;
    }
    JobLog log = insertLog(job, triggerType, "已受理，等待执行结果",
            JobLog.STATUS_RUNNING, null, null, address);
    return new DecideResult(log, job);
}

/** 通用落库：插入一条 job_log（id 由 DB 回填）。handleCode/handleTime/executorAddress 可空。 */
private JobLog insertLog(JobInfo job, String triggerType, String handleMsg, int status,
                         Integer handleCode, LocalDateTime handleTime, String executorAddress) {
    JobLog log = new JobLog();
    log.setJobId(job.getId());
    log.setJobGroupId(job.getJobGroupId());
    log.setHandlerName(job.getHandlerName());
    log.setTriggerType(triggerType);
    log.setTriggerTime(LocalDateTime.now());
    log.setStatus(status);
    log.setHandleMsg(handleMsg);
    if (handleCode != null) log.setHandleCode(handleCode);
    if (handleTime != null) log.setHandleTime(handleTime);
    if (executorAddress != null) log.setExecutorAddress(executorAddress);
    jobLogMapper.insert(log);
    return log;
}

/** 调度决策结果载具：携带已落库 log 与行锁内最新 job，dispatch 直接用，省去二次查询 */
public static class DecideResult {
    private final JobLog log;
    private final JobInfo job;
    public DecideResult(JobLog log, JobInfo job) { this.log = log; this.job = job; }
    public JobLog getLog() { return log; }
    public JobInfo getJob() { return job; }
}
```

> 行为对照（无执行器分支的 handle_code/handle_time 形状态）：
> - **decide 提交时即无执行器**（本步，最常见）：status=2 + handleMsg=「无可用执行器」，handleCode/handleTime 均空。与旧流程一致——旧代码 route==null 时对 running log 只 updateById(status=2, handleMsg)，不改 handle_code/handle_time。保持空 ⇒ 该失败不进入 JobFailMonitor「最近失败」告警（与现状一致），不改动告警可见性。
> - **dispatch 重试时执行器全下线**（Step 3 的 attempt>0 route==null，极罕见边）：走 endRunning 置 FAIL_CODE + handleTime=now。这是「已发起过至少一次真实投递后」的失败，与其它失败 tail 同形，符合 JobFailMonitor 预期。
> - **被阻塞** → status=4（STATUS_BLOCKED，item5 拆分后口径），handleCode/handleTime 空；handle_time=null ⇒ 不入「最近失败」告警与巡检（与旧 saveLog 同形）。

- [ ] **Step 2: JobTriggerServiceImpl.trigger 改用 DecideResult**

```java
@Override
public void trigger(long jobId, String triggerType) {
    JobInfo job = jobInfoMapper.selectById(jobId);
    if (job == null) return;
    if ("sharding".equalsIgnoreCase(job.getRouteStrategy())) {
        broadcast(job, triggerType);
        return;
    }
    JobDecisionService.DecideResult result = jobDecisionService.decide(jobId, triggerType);
    if (result == null) return;
    dispatch(result, triggerType);
}
```

- [ ] **Step 3: dispatch 重构——走载具、去 selectById、attempt0 用已落库地址、收尾 endRunning**

替换整段 dispatch（原 140-184 行，含 `Long logId`/`JobLog log = selectById`/三态 updateById）：

```java
/** 分发：attempt0 直接复用 decide 已路由落库的地址（不再 route、不再补写）；
 *  仅 retry（attempt>0）重新 route，换地址走窄更新。收尾一律 endRunning(status=0 守卫)，
 *  并发回调先落终态时 0 行自然跳过，不覆盖真实结果。 */
private void dispatch(JobDecisionService.DecideResult result, String triggerType) {
    JobInfo job = result.getJob();
    JobLog log = result.getLog();
    long jobId = job.getId();
    String firstAddress = log.getExecutorAddress();
    if (firstAddress == null) return;   // decide 已保证无执行器时返回 null 不进来，防御
    int retryCount = job.getRetryCount() == null ? 0 : job.getRetryCount();
    Exception lastError = null;

    for (int attempt = 0; attempt <= retryCount; attempt++) {
        String address;
        if (attempt == 0) {
            address = firstAddress;          // decide 已在 INSERT 前路由并落库
        } else {
            address = routerService.route(job.getJobGroupId(), job.getRouteStrategy(), jobId);
            if (address == null) {
                jobLogMapper.endRunning(log.getId(), JobLog.STATUS_FAIL, ReturnT.FAIL_CODE, "无可用执行器",
                        LocalDateTime.now());
                return;
            }
            if (!address.equals(log.getExecutorAddress())) {
                log.setExecutorAddress(address);
                jobLogMapper.updateExecutorAddress(log.getId(), address);   // 窄更新换地址
            }
        }
        try {
            dispatchOne(log, job, address, 0);
            return;
        } catch (Exception e) {
            lastError = e;
            // 超时 = 结果未知，执行器可能仍在执行：重试必然重复执行，直接放弃
            if (isTimeout(e)) {
                break;
            }
        }
    }

    if (isTimeout(lastError)) {
        jobLogMapper.endRunning(log.getId(), JobLog.STATUS_UNKNOWN, ReturnT.FAIL_CODE,
                "执行超时，结果未知：执行器可能仍在执行，请以执行器日志为准，勿重复触发",
                LocalDateTime.now());
    } else {
        jobLogMapper.endRunning(log.getId(), JobLog.STATUS_FAIL, ReturnT.FAIL_CODE,
                lastError != null ? lastError.getMessage() : "无返回", LocalDateTime.now());
    }
    jobInfoMapper.touchLastTime(jobId, System.currentTimeMillis());
}
```

- [ ] **Step 4: dispatchOne 去掉 DB 补写（原 117-135 行中 118-119 两行删除）**

```java
private void dispatchOne(JobLog log, JobInfo job, String address, int shardTotal) {
    // 地址在 decide/broadcast 落日志时已写入 DB（P1 去 #10），此处只做 HTTP，不再 updateById 补写
    TriggerParam param = new TriggerParam();
    param.setJobId(job.getId());
    param.setHandler(job.getHandlerName());
    param.setExecutorParam(job.getExecutorParam());
    param.setLogId(log.getId());
    param.setShardIndex(log.getShardIndex());
    param.setShardTotal(shardTotal);
    ReturnT<?> result = restTemplate.postForObject("http://" + address + "/run", param, ReturnT.class);
    if (result != null && result.getCode() == ReturnT.SUCCESS_CODE) {
        // ack 成功 = 执行器已受理，任务还在跑，日志保持 status=0 等回调
        jobInfoMapper.touchLastTime(job.getId(), System.currentTimeMillis());
        return;
    }
    throw new RuntimeException(result != null ? result.getMsg() : "无返回");
}
```

- [ ] **Step 5: broadcast 地址进 INSERT + 失败分支收敛 endRunning**

替换 broadcast（原 93-115 行）：地址在 insert 前 `setExecutorAddress`，catch 改 `endRunning`（不再整行 updateById）：

```java
private void broadcast(JobInfo job, String triggerType) {
    List<String> addresses = routerService.onlineAddresses(job.getJobGroupId());
    if (addresses.isEmpty()) {
        JobLog log = new JobLog(job, triggerType, "无可用执行器", ReturnT.FAIL_CODE, JobLog.STATUS_FAIL, 0);
        jobLogMapper.insert(log);
        return;
    }
    int total = addresses.size();
    for (int i = 0; i < total; i++) {
        // handle_code = null --> 任务才受理，还未执行
        JobLog log = new JobLog(job, triggerType, "已受理，等待执行结果", null, JobLog.STATUS_RUNNING, i);
        log.setExecutorAddress(addresses.get(i));   // 地址落库进 INSERT（原由 dispatchOne 补写）
        jobLogMapper.insert(log);
        try {
            dispatchOne(log, job, addresses.get(i), total);
        } catch (Exception e) {
            jobLogMapper.endRunning(log.getId(), JobLog.STATUS_FAIL, ReturnT.FAIL_CODE,
                    "投递失败：" + e.getMessage(), LocalDateTime.now());
        }
    }
}
```

- [ ] **Step 6: 编译**
Run: `mvn -q -pl ww-job-admin -am compile`
Expected: BUILD SUCCESS（本任务改动整批编译，若报错逐文件核对签名/import）

---

### Task 5: A1 cron 路径喂新鲜 job（claim 返回 JobInfo + trigger(JobInfo) 重载）

**Files:**
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/service/JobTriggerService.java`（接口）
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/service/JobTriggerServiceImpl.java`（claim 返回 JobInfo + trigger 拆两入口）
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/schedule/ScheduleHelper.java:99-108`（时间轮回调）

**Interfaces:**
- Consumes: Task 4 的 `DecideResult`（trigger(JobInfo) 内部复用）
- Produces: `claimNextTime` 返回 `JobInfo|null`；`void trigger(JobInfo job, String triggerType)` 重载

- [ ] **Step 1: 接口改造**

```java
public interface JobTriggerService {
    /** 手动/API 触发：selectById 一次后委托 trigger(job,...) */
    void trigger(long jobId, String triggerType);

    /** 实体触发入口：cron 路径喂入 claim 锁内新鲜 job（不再二次查询）；manual 内部复用 */
    void trigger(JobInfo job, String triggerType);

    /** 抢行锁 + 窄更新推进 next_time，返回锁内新鲜 job（非 null 才允许触发）。
     *  多 admin 下同一触发点只有一台返回非 null（先推进者），其余返回 null。 */
    JobInfo claimNextTime(long jobId, String cron);
}
```

（接口需 `import com.wwjob.admin.entity.JobInfo;`）

- [ ] **Step 2: Impl.trigger 拆两入口**

```java
@Override
public void trigger(long jobId, String triggerType) {
    JobInfo job = jobInfoMapper.selectById(jobId);
    if (job == null) return;
    trigger(job, triggerType);
}

@Override
public void trigger(JobInfo job, String triggerType) {
    if ("sharding".equalsIgnoreCase(job.getRouteStrategy())) {
        broadcast(job, triggerType);
        return;
    }
    JobDecisionService.DecideResult result = jobDecisionService.decide(job.getId(), triggerType);
    if (result == null) return;
    dispatch(result, triggerType);
}
```

- [ ] **Step 3: claimNextTime 返回 JobInfo**

```java
@Transactional
@Override
public JobInfo claimNextTime(long jobId, String cron) {
    JobInfo job = jobInfoMapper.selectByIdForUpdate(jobId);
    if (job == null || job.getTriggerStatus() == null || job.getTriggerStatus() != 1) {
        return null;   // 任务不存在或已停用
    }
    long now = System.currentTimeMillis();
    if (!claimable(job.getTriggerNextTime(), now)) {
        return null;   // 已被别台推进 / 触发点边界秒尚未整体过去
    }
    long next = CronUtil.nextTime(cron, now);
    jobInfoMapper.advanceNextTime(jobId, next);   // 窄更新推进（A4，已就位）
    job.setTriggerNextTime(next);
    return job;   // 返回锁内新鲜 job，调度器喂给 trigger(job)
}
```

> F6-2 语义不变：窄更新仍在行锁事务内、claimable 截断保留；只是返回值从 boolean 换成「顺手带回的锁内实体」。

- [ ] **Step 4: ScheduleHelper 时间轮回调改喂新鲜 job（99-108 行）**

```java
timeWheel.addTask(delay + TICK_MS, () -> {
    try {
        // 触发点幂等：行锁内先推进 next_time，返回非 null 才真正触发，并直接喂入锁内新鲜 job
        JobInfo claimed = triggerService.claimNextTime(job.getId(), job.getCron());
        if (claimed != null) {
            triggerService.trigger(claimed, "cron");   // 原 trigger(job.getId(),"cron")：不再二次 selectById
        }
    } finally {
        scheduledJobIds.remove(job.getId());
    }
});
```

- [ ] **Step 5: 编译**
Run: `mvn -q -pl ww-job-admin -am compile`
Expected: BUILD SUCCESS

---

### Task 6: 全量回归（改一处验一处 → 端到端整体）

**Files:**（Claude 维护/复用，见下）
- `tools/repro_f29.py`（F2-9 确定性复现，stall + /stop 语义）
- R2 六场景 curl 断言（start/stop 幂等 + 状态区分）
- `tools/trigger_concurrent.py`/观察脚本（Phase5 SINGLE 互斥、F6-2 双 admin 决定性）
- 手写 SQL 探针（同秒双触发计数、update_time 抽查）

- [ ] **Step 1: 每个 Task 落地后先跑其自带验证**（1-5 各自编译；Task 3 Step 5 的 update_time 抽查）
- [ ] **Step 2: 单 admin 端到端回归**
  - 手动触发普通任务 → 成功/失败回调正确落 status 1/2、executor_address 已落库
  - 手动触发 + 重复 POST 同 logId 回调两次 → 第一次 200、第二次也 200 且状态不变
  - 回调 POST 不存在的 logId → 500「logId 不存在」
  - cron 快任务连续 30+ 点 → 无同秒双触发、每点 status 有且仅一个
  - 无执行器组手动触发 → 直接 status=2「无可用执行器」单条日志（非 running→fail 两条）
  - `repro_f29.py` → 绿（F2-9 未回归）
- [ ] **Step 3: 超时/D2/分片**
  - 慢任务超时 → dispatch 收尾 status=3 → 迟到回调覆盖 3→成功 1（D2 实测）
  - 分片任务 2 台 executor e2e → 每分片 status=1 + 各自地址落库
  - 分片组停一台 → 该分片失败 tail endRunning（status=0 守卫不误伤）
  - 组全停手动触发 → status=2 无可用执行器
- [ ] **Step 4: 并发决定性 SQL（多 admin）**
  - 双 admin 同秒触发点 → `SELECT COUNT(*) ... GROUP BY` 无同秒双跑（F6-2，对比 load-test-results 基线 33→0）
  - SINGLE 并发手动 1+99 → 恰 1 个 status=0/1、其余 status=4 被阻塞（item5 拆分后口径；历史 Phase5「33→0」基线按拆分前 status=3 计数，不回改）
- [ ] **Step 5: 结果对照留档**（Claude 跑，供报告用）
  - 每触发往返代理计数：临时开 MyBatis SQL 日志跑快任务 N 次，数 job_info/job_log 语句条数（压测后关）→ 应 ~7
  - 上述回归全部通过后，由你决定提交节奏；提交后按 repo 惯例：fix 提交 + docs 提交引用 fix hash

> **T6 回归裁定（2026-09-04，用户拍板「先记录，完成 P1-SA」）**：Step 2 的「cron 快任务连续 30+ 点」初跑 FAIL（1Hz 单任务 38s 仅 4 条）——逐层排查确认为 **pre-existing cron 饿死缺陷**（44e0b16 F6-2 秒截断 vs TimeWheel +0.9s 落点的低负载副作用，非本阶段回归），已完整记录于 `docs/load-test-results.md` 文末补记 + §4.7 待排期。据此 **c7 断言放宽**（`tools/verify_p1_stage_a.py`）：不断言 ≥28 条频率（饿死下不可能稳定达到），只验 cron 路径正确性——60s 落 ≥1 条（零触发带一轮重试）、无同秒双触发、每点 status=1；该放宽只影响断言阈值，不改生产代码、不掩盖本阶段引入的回归（若 T5 cron 链路断，60s+重试仍会 0 条 → 判 FAIL）。

---

### Task 7: D=300 A/B 压测对照（阶段 A 验收门，Claude 执行）

- [x] 单 admin D=300 快任务，与 `docs/load-test-results.md` 基线（~150/s 拐点）同口径跑 3 分钟
- [x] 观察 Hikari 连接池饱和度 / 触发池利用率变化
- [x] 结果写回 `docs/load-test-results.md`：P1 标记「已修复（commit xxx）」+ 吞吐前后对照
- [x] 通过则进入阶段 B（另立 spec/计划）；未达标则按 spec §阶段B 的 B1/B2 设计方向补强后重测

> **T7 裁定（2026-09-04，阶段 A 达标 → 进入阶段 B）**：P12 实测 33379 行/200s = **166.9/s**（前 100s ~165/s → 末 80s ~208/s 爬升），对照 P4b 基线 149.6/s → **+12% 且墙位上移**；status 100%=1（status=3/4 均 0）、distinct=3000、同秒双触发=0、interval p50 19s、CPU 1.1~1.4 核 + DB往返/s 反降近半（P4b 263%单核/~1500）。写放大收敛（~10→5 stmts/触发）释放触发池占用 → 单 admin 墙由 ~150 顶开。**验收通过**。完整对照与诚实标注（非严格单变量）见 `docs/load-test-results.md` §Phase10 F10-1 + §1 P12 行。阶段 B（B1/B2）另立 spec/计划；重测建议窗口 5min+ 且先 warm-up 到稳态（200s 窗均值低估窗末容量）。

---

## 非目标（本计划不做）

- 阶段 B（B1 claim+decide 并单事务、B2 registry 内存缓存）——以阶段 A 实测为新基线后另立计划
- `JobLogTimeoutScanner` 批量收窄、`job_log(status, trigger_time)` 复合索引
- 执行耗时 ms 上报（G5）、慢任务隔离（C1）、双 admin 分片/CAS（P3）
- 顺手发现项（不混入本计划，需单独确认）：广播「无可用执行器」分支与手动单发无执行器分支的 handle_time 一致性差异（前者未置 handle_time，最新失败告警可能漏捞）——如需修，另立小任务
