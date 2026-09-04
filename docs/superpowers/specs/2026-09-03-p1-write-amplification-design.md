# P1 触发写放大压缩设计（两阶段）

> 依据 `docs/load-test-report.md` §6-P1：每触发 ~10 次 DB 往返、3 次独立事务 commit，是瓶颈链每道墙的公共分母。本文给出测量基线 + 两阶段减往返设计。

## 目标

把 cron 快任务触发路径的每触发 DB 往返从 **~10 降到 ~7（阶段 A）再降到 ~5（阶段 B）**，回调收账从「select + 全行 updateById」2 往返压缩为 **1 条条件窄更新**；每次压缩都保留既有正确性红绿灯，并以 D=300 A/B 压测留下可归因的吞吐证据。

## 现状测量基线（代码级）

### cron 快任务触发路径（非 sharding，SINGLE）—— ~10 次往返

| # | 动作 | 位置 | 事务 | 冗余类别 |
|---|---|---|---|---|
| 1 | scheduleLoop `selectList(job_info)` | ScheduleHelper:68 | 无 | 共享，每 tick 1 次（可忽略） |
| 2 | claim tx：`selectByIdForUpdate(job)` | JobTriggerServiceImpl:63 | tx#1 | 必须（行锁） |
| 3 | claim：`updateById(job)` 全行推进 next_time | JobTriggerServiceImpl:75 | tx#1 | **全行写放大** + update_time 失真 |
| 4 | trigger：`selectById(job)` 重查 | JobTriggerServiceImpl:48 | 无 | **冗余**：claim 刚锁过同一行 |
| 5 | decide tx：`selectByIdForUpdate(job)` 二次锁 | JobDecisionService:31 | tx#2 | 必须（SINGLE 互斥） |
| 6 | decide：`countRunning(job_log)`（SINGLE） | JobDecisionService:37 | tx#2 | 必须（SINGLE gate） |
| 7 | decide：`insert job_log`（running，无地址） | JobDecisionService:44/57 | tx#2 | 必须 |
| 8 | dispatch：`selectById(job_log)` 重查自己刚 insert 的 log | JobTriggerServiceImpl:142 | 无 | **冗余** |
| 9 | route：`selectList(job_registry)` | ExecutorRouterService:43 | 无 | 见 B2（内存缓存可归零） |
| 10 | dispatchOne：`updateById(job_log)` 补写 executor_address | JobTriggerServiceImpl:119 | 无 | **冗余**：地址本可进 INSERT |
| 11 | ack 后 `touchLastTime(job)` | JobTriggerServiceImpl:130 | 无 | 已窄更新（F2-9），保留 |

### 回调收账路径（每完成一单）—— 2 往返 + 全列写

- `selectById(job_log)` + `updateById(job_log)`（全列写，含 createTime） → CallbackController:29/38

## 正确性不变式（红绿灯，任何改动不得破坏）

1. **F6-2**：cron 触发点幂等——多 admin 下同一点仅一台在行锁内 claim 成功并推进 next_time，其余跳过。秒级截断 `claimable(lastNext, nowSec)` 保留。
2. **锁窗口**：行锁随事务 commit 释放；HTTP 分发（10s 读超时）绝不在行锁事务内执行。
3. **D3 SINGLE**：`countRunning` 判定与 running log 的 insert 必须同处一行锁事务，保证「判-插」原子；重叠触发落 status=4 被阻塞日志（item5 拆分后口径，原为 3）。
4. **F2-9**：调度路径对 `job_info` 只做窄列更新（next/last/status），绝不整实体 `updateById` 写回。
5. **D2 迟到回调**：超时被置 3（dispatch tail 或巡检）后，迟到回调仍可覆盖 3→1/2 最终一致。
6. **幂等收账**：job_log 的终态更新一律条件窄更新 + status 守卫，绝不整行 `updateById` 覆盖并发的 status/handle。
7. **分片广播**：每分片独立 job_log；不经 SINGLE gate、不经 job 行锁（既有设计，不因本改动而并轨）。

---

## 阶段 A：清理型减往返（~10 → ~7 + 回调 2→1）

目标：去掉 3 个冗余往返（#4/#8/#10），全行更新收敛为窄更新，回调单条收账。**不合并锁事务**（claim/decide 仍是两个独立 tx，红绿灯 1/2/3 不变式由 tx 边界自然维持）。

### A1. claim 返回锁内新鲜 job → cron 路径不再重查（去 #4）

**现状**：ScheduleHelper 时间轮回调 → `claimNextTime(jobId, cron)`（boolean）→ 为 true 再 `trigger(jobId,"cron")` → Impl:48 `selectById` 重查 job 拿 routeStrategy 分支 + 喂 dispatch。

**改**：`claimNextTime` 返回值 `boolean → JobInfo`（锁内已 `selectByIdForUpdate`，顺手把该行返回；null = 停用/不可 claim/不存在）。ScheduleHelper 回调改为：

```java
JobInfo claimed = triggerService.claimNextTime(job.getId(), job.getCron());
if (claimed != null) triggerService.trigger(claimed, "cron");
```

接口增加按实体触发入口（cron 喂入 claim 的锁内新鲜 job，**不再重查**）：

```java
// JobTriggerService
void trigger(JobInfo job, String triggerType);   // cron：喂入新鲜实体（claim 已锁读）；manual 内部也走它
void trigger(long jobId, String triggerType);    // manual/API：selectById 一次后委托 trigger(job,...)
JobInfo claimNextTime(long jobId, String cron);  // 返回值 boolean→JobInfo
```

**新鲜度论证**：dispatch 真正使用的 job 来自 **decide 行锁内重读的最新行**（见 A2），不依赖 caller 喂入的实体；caller 实体只用于 trigger 内做 routeStrategy 分支，其新鲜度 = claim 锁读时刻（比现状 selectById 更早约零、等价于现状 claim 后的那次重查），无回归。manual 路径保留唯一一次 selectById（id 入口必须），非热路径不优化。

**sharding 分支**：`trigger(job,…)` 内 `routeStrategy == "sharding"` → `broadcast(job, type)`，cron sharding 任务经 claim 后喂入的即锁内新鲜 job，broadcast 直接用。

**风险**：JobTriggerService 接口签名变化（claimNextTime 返回类型、新增 trigger(job,…)）。调用点仅 ScheduleHelper 一处 + 测试；改动局部。

### A2. decide 返回「插入的 log 实体 + 锁内 job」→ dispatch 不再重查（去 #8）

**现状**：decide 只返回 `Long logId`，dispatch 拿 id 再 `selectById(logId)`（Impl:142）取实体。decide 行锁内本就有最新 `JobInfo` 与刚 insert 的 `JobLog`（id 由 insert 回填）。

**改**：decide 返回值 `Long → DecideResult`（内部载具，放 service 包即可）：

```java
class DecideResult { final JobLog log; final JobInfo job; }
// decide(jobId, triggerType): 阻塞/无 job/无执行器直接落 log 后 → null
```

`dispatch(DecideResult r, String type)`：直接用 `r.log`（不再 select）+ `r.job`（锁内最新，喂 triggerParam/retryCount），删除 Impl:142 的 `selectById(logId)`。

**语义**：dispatch 现在拿到的 job = decide 行锁内重读的最新行，比现状（trigger 的 selectById，可能早于 decide 锁读）更接近 dispatch 时刻，无回归且有轻微改善。

### A3. 路由移进门后 + executor_address 进 INSERT（去 #10）

**现状**：decide 只 gate + insert running log（地址 null）；dispatch 每 attempt `route()` 拿地址 → dispatchOne `updateById(log)` 补写 executor_address（#10）→ HTTP。

**改**：decide 依赖 `ExecutorRouterService`；SINGLE gate 通过后、insert 之前先 route，地址直接写进 INSERT：

```java
// JobDecisionService.decide（gate 之后）
String addr = routerService.route(job.getJobGroupId(), job.getRouteStrategy(), jobId);
JobLog log = new JobLog(job, type, "已受理，等待执行结果", null, RUNNING, 0);
log.setExecutorAddress(addr);     // 无执行器时 addr=null → 直接落 status=2「无可用执行器」log 并返回 null
jobLogMapper.insert(log);
```

- **空地址**：insert 前 route==null → 直接落 `status=2`「无可用执行器」fail log 返回 null（现状是 running→fail 两次写，此为一处写，行为等价）。
- **dispatch**：attempt0 地址 = `r.log.getExecutorAddress()`（**零补写**）；仅当 retry（attempt>0）且新路由地址不同时，`UPDATE job_log SET executor_address=? WHERE id=?`（窄更新）。fast path（retryCount=0 或一次成功）完全删掉 #10。
- **broadcast**：地址集合在 insert 前已知 → 每分片 `log.setExecutorAddress(addresses.get(i))` 后再 insert，dispatchOne 同样删掉地址补写。broadcast 的失败 tail 收敛为窄更新（见 A6）。
- **route 位置语义**：SINGLE 被阻塞的触发（Phase5 里 99%）在 gate 就返回，不消耗 route、不做 registry select（不浪费）。route 只在「真会派发」的日志上发生一次，锁内短暂多一次非锁定 registry 读——若 A/B 显示它拉长行锁持有，即为阶段 B2（内存缓存归零）的直接动因。

### A4. job_info 推进改窄更新（去全行写，顺带修 update_time 失真）

**现状**：claim（Impl:75）与 scheduleIfNeeded catch-up（ScheduleHelper:92）都用 `updateById(job)` 全行写。全行 SET 把**加载时的旧 update_time 显式写回** → `ON UPDATE CURRENT_TIMESTAMP` 永不触发 → update_time 恒等于 create_time（与 multi-admin 阶段已记录的坑同源）。且全行写放大。

**改**：JobInfoMapper 新增窄更新，两处调用点替换：

```java
/** 窄更新：只推 next_time，不写回整行（整行写会带旧 update_time，ON UPDATE 不触发）。行锁内/幂等边界安全 */
@Update("UPDATE job_info SET trigger_next_time = #{nextTime} WHERE id = #{id}")
int advanceNextTime(@Param("id") long id, @Param("nextTime") long nextTime);
```

- claim：锁内已串行，narrow 安全；返回值仍 true/false（claimable 判定不变）。
- catch-up：无行锁，但多 admin 各自算出的下一边界相同，last-write-wins 幂等，narrow 安全。
- 附带收益：`update_time` 恢复真实最近变更（需在回归里抽查一列）。

### A5. 回调收账改单条条件窄更新（2 往返 → 1，D2 幂等）

**现状**：CallbackController:29 `selectById` + 全行 `updateById`。

**改**：JobLogMapper 新增，controller 逻辑替换：

```java
/** 收账终态条件窄更新。WHERE status IN (0,3)：运行中→终态；被超时置 3 后迟到回调仍覆盖为 1/2（D2）。
 *  已终态(1/2)重复回调 → 0 行 → 幂等跳过。返回行数供「不存在」判错。 */
@Update("UPDATE job_log SET status=#{status}, handle_code=#{handleCode}, handle_msg=#{handleMsg}, handle_time=#{handleTime} "
        + "WHERE id=#{id} AND status IN (0, 3)")
int completeById(@Param("id") long id, @Param("status") int status, @Param("handleCode") int handleCode,
                 @Param("handleMsg") String handleMsg, @Param("handleTime") LocalDateTime handleTime);
```

```java
@PostMapping("/callback")
public ReturnT<String> callback(@RequestBody CallbackParam param) {
    int status = param.getHandleCode() == ReturnT.SUCCESS_CODE ? JobLog.STATUS_SUCCESS : JobLog.STATUS_FAIL;
    LocalDateTime handleTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(param.getHandleTime()), ZoneId.systemDefault());
    int rows = jobLogMapper.completeById(param.getLogId(), status, param.getHandleCode(), param.getHandleMsg(), handleTime);
    if (rows > 0) return ReturnT.success();
    // 0 行：幂等跳过(已终态) 或 logId 不存在。保留「不存在」报错（executor 误报排查），仅在此分支查一次
    return jobLogMapper.selectById(param.getLogId()) == null
            ? ReturnT.fail("logId 不存在:" + param.getLogId())
            : ReturnT.success("已是最新状态，忽略重复回调");
}
```

**幂等性论证**：executor 侧 CallbackReporter 对非 200 会退避重试 3 次——本设计对重复/迟到回调返回 200，**杜绝重试风暴**（若对已终态返回 fail，会让 executor 无谓重试 3 次才放弃）。

### A6. dispatch 收尾与 broadcast 失败 tail 收敛窄更新

**现状**：dispatch 超时/失败 tail（Impl:171-181）与 broadcast 失败分支（Impl:108-112）用 `updateById(log)` 全行把 running log 置 2/3。

**改**：JobLogMapper 新增收尾窄更新（带 status=0 守卫，杜绝覆盖并发回调结果）：

```java
/** 调度侧收尾：仅当日志仍在运行(0)才置终态。若并发回调已落 1/2，0 行跳过，不覆盖真实结果 */
@Update("UPDATE job_log SET status=#{status}, handle_code=#{handleCode}, handle_msg=#{handleMsg}, handle_time=#{handleTime} "
        + "WHERE id=#{id} AND status=0")
int endRunning(@Param("id") long id, @Param("status") int status, @Param("handleCode") int handleCode,
               @Param("handleMsg") String handleMsg, @Param("handleTime") LocalDateTime handleTime);
```

dispatch tail 与 broadcast catch 改用 `endRunning`（仍保留 tail 的 `touchLastTime(job)`）。

### 阶段 A 文件改动地图

| 文件 | 改动 |
|---|---|
| `JobInfoMapper` | + `advanceNextTime` |
| `JobLogMapper` | + `completeById`、`endRunning`、`updateExecutorAddress`（retry 专用，窄） |
| `JobDecisionService` | 返回值 `Long→DecideResult`；+ `ExecutorRouterService` 依赖；gate 后 route、insert 带地址；空地址落 status=2 返回 null |
| `JobTriggerService` | `claimNextTime` 返回 `JobInfo`；+ `trigger(JobInfo,…)` |
| `JobTriggerServiceImpl` | `trigger(jobId)` select 后委托 `trigger(job)`；`trigger(job)` 分支；`dispatch(DecideResult,…)` 去 #8；`dispatchOne` 去地址补写；retry 地址窄更新；tail 用 `endRunning` |
| `ScheduleHelper` | 回调用 `JobInfo claimed = claimNextTime(...)` 后 `trigger(claimed,…)` |
| `CallbackController` | 改 `completeById` + 幂等/不存在语义 |
| `ExecutorRouterService` | 不动（阶段 A route 仍 DB 读，位置改到 decide 门后） |
| 测试工具 | `tools/*` 回归脚本按需小改（断言不变） |

### 阶段 A 验证口径

1. **回归（改一处验一处，先红后绿）**
   - `repro_f29.py` 确定性复现仍绿（stall + /stop 语义不回归）；
   - R2 六场景 curl 断言全绿（start/stop 语义 + 行锁写入路径已改窄，需复跑）；
   - Phase5 SINGLE 并发 1+99 decisive SQL（gate+insert 仍在 decide 单 tx，应保持）；
   - D2：慢任务触发 → dispatch 超时置 3 → 迟到回调覆盖 3→1 实测；
   - 重复回调（同 logId 二次 POST）→ 200 + 状态不变；
   - F6-2 同秒双 claim 决定性 SQL（多 admin）；
   - 分片广播 2 executor e2e + 停一台 + 全停（address 落库与 fail tail）；
   - 手动触发、无执行器（decide 空地址落 status=2）、`update_time` 抽查恢复变化。
2. **A/B 压测**：D=300 快任务单 admin，与 150/s 基线对照吞吐；观察 Hikari 连接池饱和度/触发池利用率；记录每触发往返代理数（可临时开 MyBatis SQL 日志数次数，压测后关）。

---

## 阶段 B：结构型（阶段 A 验收后再设计细化与实现）

阶段 B 建立在新代码形态上，本文给设计方向与必须守住的不变式；实现前另写实现计划。

### B1. claim + decide 合并单事务

A 之后 cron 路径仍为 claim tx + decide tx 两次取行锁、两次 commit。B1 将其并为**一个** `@Transactional`：锁行 → `claimable` 判定 →（SINGLE gate count）→ route → insert running(带地址) → `advanceNextTime` → commit → 返回 {log, job}；HTTP 分发仍在事务外。

- 每触发往返：A 的 **7 → 6**（锁内：锁读 1 + advance 1 + count 1 + insert 1 = 4 次，同 tx 内不再有 claim/decide 两次取锁；另加 route 1（见 B2）；锁外 touchLastTime 1）。**再配 B2 route 走内存 → 5**。
- 不变式 1/2/3 论证：advance 仍在行锁内先于分发（F6-2 语义保留）；HTTP 在 commit 后（锁窗口变长 = gate+insert+route+advance 全在锁内，约几毫秒，需 A/B 验证对行锁争用的影响——route 若未上 B2 会是锁内最长项，故 B1 与 B2 需配套评估先后）。
- 手动 trigger（无 claim）保持 decide 路径；sharding 保持广播路径。

### B2. 在线执行器内存缓存（route 0 DB）

executor 心跳已广播到每台 admin（ExecutorRegistry broadcast）→ 每台 admin 可自维护 `Map<jobGroupId, Map<registryValue, heartbeatTime>>`：`RegistryService.upsert` 写 DB 同时 touch 内存；`RegistryCleaner`（10s）同步清 90s 离线；`onlineAddresses`/`route` 读内存，缓存空时回退 DB。每触发省 #9 registry select（A 计数 -1 → 配合 B1 到 ~4-5）。

- 正确性论证：缓存由本 admin 收到的心跳广播驱动，多 admin 集群各台收敛一致（失联 admin 至多滞后一个心跳周期，且 route 选执行器本就允许次级新鲜）；executor 静默死亡由 90s cleaner 兜底（与现状 DB 口径一致）。
- 边界：admin 重启后缓存空 → 回退 DB 读 + 首个心跳周期自愈；`/registry/list` 前端展示仍走 DB 或同缓存需统一口径。

### 阶段 B 验证追加

- 单 admin D=300 A/B（A 结果对照）；多 admin 无重复决定性 SQL（F6-2 复用）；executor kill → 90s 内从缓存剔除、route 不再选中；双 admin 各自心跳驱动缓存收敛一致（P6 场景）。

---

## 非目标（本次不做，记录待办）

- 分片广播并轨 SINGLE gate / 行锁（违背既有设计 D2，不做）。
- `JobLogTimeoutScanner` 批量收窄 + `job_log(status, trigger_time)` 复合索引：30s 低频巡检 + 大表全扫，独立于每触发写放大，另立项（对应 G4/G5 关联）。
- 执行耗时 ms 级上报（G5）、慢任务隔离（C1）、双 admin 分片/CAS（P3）——分别对应独立改进，不在 P1。

## 交付物验收清单

- [ ] 阶段 A 代码合入后：R1 回归套件全绿、六场景不变式实测通过、A/B 压测报告归档到 `docs/load-test-results.md`
- [ ] 报告 §6-P1 更新为「已修复（commit xxx）+ 实测前后吞吐对照」
- [ ] 阶段 B 以 A 的实测为新基线重定目标后，另立实现计划
