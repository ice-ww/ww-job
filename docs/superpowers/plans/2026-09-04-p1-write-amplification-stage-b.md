# P1 Stage B 实现计划：claim+decide 合并单事务 + 在线执行器内存缓存

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 cron 快任务触发线程热路径的每触发 DB 往返从 **7 降到 5**、决策事务从 **2 个降到 1 个**（B1 合并 claim+decide 单事务 + B2 route 走内存缓存归零 registry 读）。

**Architecture:** 两块正交改动，**B2 先、B1 后**（易→险，各自独立回归）。B2 = 新 `RegistryCacheService`（事件驱动写穿，无 DB 无新线程）：RegistryService.upsert/offline 与 RegistryCleaner 写缓存，`ExecutorRouterService.onlineAddresses` 读缓存、空集回退 DB 并水合。B1 = 把 claim（锁行/status/claimable/advance）与 decide（SINGLE gate/route/insert）收敛到 JobDecisionService 的锁下核心，新增 `decideCron` 单事务入口（仅非 sharding cron），ScheduleHelper 环回调按 routeStrategy 分支；HTTP 分发一律在事务外。

**Tech Stack:** Java 21 / Spring Boot / MyBatis-Plus / MySQL；验证用 `tools/*.py`（pymysql+requests，general_log 探针）+ curl 决定性 SQL，无 admin 单测框架。

**Spec:** `docs/superpowers/specs/2026-09-04-p1-write-amplification-stage-b-design.md`（本 plan 从 spec 论证；执行者两个文件都要读，spec 是权威）。

## Global Constraints（每条任务的隐含要求，来自 spec）

- **账目目标**：cron 触发线程热路径 DB 往返 7 → 5；决策事务 2 → 1。probe "5.0" 是手动口径，**cron 基线是 7，一切声明用 7**，不得引用 5.0。
- **红绿灯 1-7（父 spec）全部不放松**：F6-2 触发点幂等（秒截断 `claimable` 保留）；锁窗口 HTTP 绝不在事务内；D3 SINGLE 判-插同锁原子；F2-9 job_info 只窄更新；D2 迟到回调可覆盖 3；收账 IN(0,3) 条件窄更新；分片广播独立。
- **B-1 边界消费次序**（本 spec 钉死）：合并事务内 `advanceNextTime` 在 **claimable 通过后立即执行、先于 SINGLE gate** —— blocked/no-executor 分支也必须消费本边界。
- **B-2 回退可见性**：route 缓存"组内无新鲜"必须回退 DB 复核并水合，绝不得仅凭本 admin 缓存判"无执行器"。
- **事务纪律**：`claimLocked` / `decideUnderLock` / `RegistryCacheService` 方法**一律不带 `@Transactional`**（加入调用方事务）；合并事务只由 proxy 可见的 `@Transactional` 入口（`decideCron`、`claimNextTime`）开启。禁止 `REQUIRES` 魔法。
- **时钟/排序**：缓存值用 admin JVM `LocalDateTime`（与 DB `heartbeat_time` 写入、RegistryCleaner 阈值同域，不用 `NOW()`）；route 输出按 registry_value **字典序**（跨实例/重启确定性，spec §3 裁定）。`/registry/list` 与仪表盘在线数**仍走 DB**，不改。
- **在线窗口**：一律用 `JobRegistry.ONLINE_SECONDS`(90) / executor 心跳 30s 广播的事实，不改产品参数。
- **协作模式**：Task 1-3 由 ice-ww 自研实现；Task 0/4 由 Claude 执行（写工具 + 跑回归/A/B）。提交由用户决定时机，本 plan 的 commit 步骤为预留。

**验证环境**（Task 0/4 需要）：dev admin(localhost:8080, db ww_job) + executor(8081) + dev MySQL(3306) 就绪（见 memory loadtest-env-playbook 的 dev 拓扑；8080 空则先在 IDEA 起 dev admin）。Task 4 的 D=300 需切 loadtest 拓扑（3307 容器 + loadtest profile 起 admin）。

---

### Task 0: cron 口径写放大基线（Claude，前置红基线）

**Files:**
- Create: `tools/probe_write_amp_cron.py`

**Interfaces:**
- Consumes: 现行代码（B 未合入）
- Produces: RED 基线存档（每触发 7 往返 / 决策两段 BEGIN…COMMIT），供 Task 4 对照

- [ ] **Step 1: 写 `tools/probe_write_amp_cron.py`**（模式仿 `tools/probe_write_amp.py`，但驱动 **cron** 触发）：
  - 建 ~30 个 scratch job（同组 1、cron `*/1 * * * * ?`、`blockStrategy="SINGLE"`、routeStrategy `round_robin`、triggerStatus=1），开 `general_log`(TABLE) 抓 60s 窗，停后清理。
  - 按 `INSERT INTO job_log` 条数（锚 = 触发数 F）计 per-fire；分类计数仿 probe_write_amp 并**排除噪音**：job_info 里 `trigger_status AND trigger_next_time` 的调度扫描剔除；job_registry 仅数 `SELECT … FROM job_registry`（route 读）——心跳是 INSERT、cleaner 是 DELETE，剔除。
  - 输出：`job_info 读/写`、`job_log INSERT/写`、`job_registry SELECT` 各自 per-fire；断言触发线程合计 = 7、decision 区出现两段 `BEGIN…COMMIT` 分组；打印并写日志文件 `tools/_probe_b_red.txt`。
  - 时间窗过滤全用 python-local（上海）datetime（F8-4 纪律），不用 NOW()。
- [ ] **Step 2: dev 拓扑就绪后运行** `python tools/probe_write_amp_cron.py`，断言 **PASS（RED：7 往返/两段）**，存档输出。若 env 未起，先按 playbook 起。
- [ ] **Step 3: 结果归档** 记入 Task 4 对照文件（不提交 git，等收口）。

---

### Task 1: B2 在线执行器内存缓存（ice-ww 自研）

**Files:**
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/service/RegistryCacheService.java`
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/service/RegistryService.java`（upsert/offline 写缓存）
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/service/RegistryCleaner.java`（clean 后扫缓存）
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/service/ExecutorRouterService.java`（读缓存 + 空回退 DB + 水合）

**Interfaces:**
- Consumes: `JobRegistry.ONLINE_SECONDS`=90、`JobRegistryMapper`（upsert/selectList/delete）、RegistryParam
- Produces: `RegistryCacheService`（touch/remove/online/prune）供 Task 2-3 沿用；route 从此稳态 0 DB（registry SELECT≈0，Task 4 实锤）

- [ ] **Step 1: 新建 `RegistryCacheService`**（无 DB、无自身线程、不带 @Transactional）：

```java
package com.wwjob.admin.service;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 在线执行器内存缓存：由本 admin 收到的注册/心跳/下线广播驱动（写穿），供 route 读，稳态 0 DB。
 * 无 DB、无自身线程；值 = LocalDateTime（admin JVM 时钟域，与 DB heartbeat_time 写入、RegistryCleaner 阈值同域）。
 * 非权威：读侧判「组内无新鲜」必须由调用方回退 DB（B-2），本类只做内存快照。
 * 并发：ConcurrentHashMap；online/prune 期间并发 touch 插入的必为新值（> cutoff），不会被误清。
 */
@Service
public class RegistryCacheService {
    /** jobGroupId -> (registryValue -> 最近心跳时刻) */
    private final ConcurrentMap<Long, ConcurrentMap<String, LocalDateTime>> cache = new ConcurrentHashMap<>();

    /** DB upsert 成功后调用，值用与 DB 写入同一 now。 */
    public void touch(long jobGroupId, String registryValue, LocalDateTime heartbeatTime) {
        cache.computeIfAbsent(jobGroupId, k -> new ConcurrentHashMap<>()).put(registryValue, heartbeatTime);
    }

    /** DB offline 删行成功后调用：优雅下线立即从路由剔除。 */
    public void remove(long jobGroupId, String registryValue) {
        ConcurrentMap<String, LocalDateTime> group = cache.get(jobGroupId);
        if (group != null) {
            group.remove(registryValue);
        }
    }

    /** 新鲜（heartbeatTime >= cutoff）的在线地址，registry_value 字典序（跨实例/重启确定性，FIRST 语义裁定）。
     *  无新鲜 → 空列表（调用方据此回退 DB 复核）。 */
    public List<String> online(long jobGroupId, LocalDateTime cutoff) {
        ConcurrentMap<String, LocalDateTime> group = cache.get(jobGroupId);
        if (group == null || group.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> fresh = new ArrayList<>();
        for (Map.Entry<String, LocalDateTime> e : group.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBefore(cutoff)) {
                fresh.add(e.getKey());
            }
        }
        fresh.sort(Comparator.naturalOrder());
        return fresh;
    }

    /** 清扫 cutoff 前的陈旧项（内存有界；读侧懒过滤才是权威）。返回清理项数。 */
    public int prune(LocalDateTime cutoff) {
        int removed = 0;
        for (Map.Entry<Long, ConcurrentMap<String, LocalDateTime>> g : cache.entrySet()) {
            ConcurrentMap<String, LocalDateTime> group = g.getValue();
            int before = group.size();
            group.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isBefore(cutoff));
            removed += before - group.size();
            if (group.isEmpty()) {
                cache.remove(g.getKey(), group);
            }
        }
        return removed;
    }
}
```

- [ ] **Step 2: `RegistryService` 注入缓存并写穿**（构造器加 `RegistryCacheService registryCacheService`；upsert 捕获同一 `now` 供 DB 与缓存）：
  - upsert 方法体内（原第 41 行 `registryMapper.upsert(group.getId(), ..., LocalDateTime.now());`）改为：
```java
        LocalDateTime now = LocalDateTime.now();
        registryMapper.upsert(group.getId(), param.getRegistryKey(), param.getRegistryValue(), now);
        registryCacheService.touch(group.getId(), param.getRegistryValue(), now);
        return ReturnT.success();
```
  - offline 方法体 delete 成功后（delete 语句后）追加：
```java
        registryCacheService.remove(group.getId(), param.getRegistryValue());
```
  - `registry` 与 `heartbeat` 都走私有 `upsert`，天然双覆盖（/registry 与 /heartbeat 均命中）。

- [ ] **Step 3: `RegistryCleaner` 注入缓存并同步清扫**（构造器加参数；clean() 在 `registryMapper.delete(...)` 后追加同阈值清扫）：
```java
        registryCacheService.prune(threshold);   // 同 90s 阈值：缓存与 DB cleaner 同语义
```
  - 注：此时 `threshold` 局部变量已是 `LocalDateTime.now().minusSeconds(JobRegistry.ONLINE_SECONDS)`，直接复用。

- [ ] **Step 4: `ExecutorRouterService.onlineAddresses` 改读缓存、空集回退 DB 并水合**（构造器加 `RegistryCacheService registryCacheService`；方法体替换为）：
```java
    public List<String> onlineAddresses(long jobGroupId) {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(JobRegistry.ONLINE_SECONDS);
        // 稳态：缓存有新鲜 → 0 DB（B2 目标）
        List<String> cached = registryCacheService.online(jobGroupId, threshold);
        if (!cached.isEmpty()) {
            return cached;
        }
        // B-2：组内无新鲜（冷启动/与执行器分区/全部离线）→ 回退 DB 复核并水合，语义与现状 DB 逐格一致
        List<JobRegistry> rows = registryMapper.selectList(new QueryWrapper<JobRegistry>()
                        .eq("job_group_id", jobGroupId)
                        .ge("heartbeat_time", threshold));
        if (!rows.isEmpty()) {
            rows.forEach(r -> registryCacheService.touch(jobGroupId, r.getRegistryValue(), r.getHeartbeatTime()));
        }
        return rows.stream().map(JobRegistry::getRegistryValue).collect(Collectors.toList());
    }
```
  - route()/broadcast 走 onlineAddresses，自动共享；`route`（多执行器组 FIRST 序从 DB 行序改值序）是 spec §3 裁定，集合正确性不变。

- [ ] **Step 5: 编译**
```
mvn -q -pl ww-job-admin -am compile
```
Expected: BUILD SUCCESS（三个构造器注入改动编译通过）。

- [ ] **Step 6: e2e 语义回归（行为不变）**：重启 admin 使新代码生效后跑：
```
python tools/verify_p1_stage_a.py fast
python tools/verify_registry_hardening.py        # 注册/心跳/下线 e2e 六验证
```
Expected: 全 PASS。另手动：executor 注册后 `GET /registry/list` 仍见行；触发任务成功（route 走水合/DB 回退）。

- [ ] **Step 7: Commit**（用户决定时机）
```
git add ww-job-admin/src/main/java/com/wwjob/admin/service/RegistryCacheService.java
git add ww-job-admin/src/main/java/com/wwjob/admin/service/RegistryService.java
git add ww-job-admin/src/main/java/com/wwjob/admin/service/RegistryCleaner.java
git add ww-job-admin/src/main/java/com/wwjob/admin/service/ExecutorRouterService.java
git commit -m "feat: B2 在线执行器内存缓存——route 稳态 0 DB，空集回退 DB 水合（spec 2026-09-04 §3）"
```

---

### Task 2: B1a 锁下核心重构（ice-ww 自研，行为零变化）

**Files:**
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/service/JobDecisionService.java`
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/service/JobTriggerServiceImpl.java`
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/service/JobTriggerService.java`（接口注释仅说明，无签名变化——本任务不加方法）

**Interfaces:**
- Consumes: `JobInfoMapper`/`JobLogMapper`/`ExecutorRouterService`（JobDecisionService 已注入三者）、`CronUtil`
- Produces: JobDecisionService 公开非事务锁下核心 `claimLocked(long,String)` → `JobInfo`、`decideUnderLock(JobInfo,String)` → `DecideResult`；静态 `claimable` 迁入 JobDecisionService；JobTriggerServiceImpl.claimNextTime 改为委托。Task 3 的 `decideCron` 与 Task 4 探针依赖这两个核心。

- [ ] **Step 1: `JobDecisionService` 迁入静态 `claimable`**（从 JobTriggerServiceImpl:83-94 原样搬入，含 javadoc 秒截断论证），类内新增：
```java
    /** 触发点是否已可 claim：next_time 恒为秒边界（CronUtil 秒精度），毫秒级 now 直接比
     *  会在边界秒翻过时误放行相邻点（败者 A 推到 10:47:04.000、败者 B 在 10:47:04.001 读到
     *  10:47:04.000 > 10:47:04.001 为假 → 误 claim → 同秒双触发，F6-2）。
     *  截断到秒：边界秒整体过去（nowSec > lastNext）才放行，杜绝同秒双 claim。 */
    static boolean claimable(Long lastNext, long now) {
        if (lastNext == null) return false;
        long nowSec = now - (now % 1000);
        return lastNext < nowSec;
    }
```
  注：同包 JobTriggerServiceImpl 曾持有，本任务删彼留此，单一属主。

- [ ] **Step 2: `JobDecisionService` 抽出锁下核心 `claimLocked` / `decideUnderLock`**，`decide` 收敛（**两个核心均不带 @Transactional**）。
  - 2a 新增 `claimLocked`（完整代码）：
```java
    /** 锁下 claim 核心（非事务，须在调用方持锁事务内）：status 门 → claimable 门 → advanceNextTime。
     *  停用 / 边界未到 / 已被别台推进 → null。advance 先于一切 gate（B-1：blocked 也消费本边界）。 */
    public JobInfo claimLocked(long jobId, String cron) {
        JobInfo job = jobInfoMapper.selectByIdForUpdate(jobId);
        if (job == null || job.getTriggerStatus() == null || job.getTriggerStatus() != 1) {
            return null;   // 任务不存在或已停用
        }
        long now = System.currentTimeMillis();
        if (!claimable(job.getTriggerNextTime(), now)) {
            return null;   // 已被别台推进 / 触发点边界秒尚未整体过去
        }
        long next = CronUtil.nextTime(cron, now);
        jobInfoMapper.advanceNextTime(jobId, next);   // A4 窄更新
        job.setTriggerNextTime(next);
        return job;   // 推进后的锁内新鲜 job
    }
```
  - 2b 新增 `decideUnderLock(JobInfo job, String triggerType)`：把现 decide() 方法体内 **selectByIdForUpdate 之后、方法右括号之前的所有语句（当前 36-62 行）整体原样搬入**，含 43-44 阻塞口径注释、53-55 告警可见性注释等全部注释文字，一字不改；只做两处参数替换：第 41 行 `countRunning(jobId)` → `countRunning(job.getId())`、第 51 行 `route(..., jobId)` → `route(..., job.getId())`。签名不带 @Transactional，顶部加 javadoc：`锁下决策核心（非事务，须在调用方持锁事务内）：SINGLE gate → route → INSERT。入参 job 必须是行锁内读到的最新行（claimLocked/decide 已保证）。`
  - 2c 现 decide()（34-63 行）收敛为薄 wrapper（@Transactional 保留在方法级，手动/API 语义不变）：
```java
    /** 手动/API 决策：行锁读 → 锁下决策核心。事务在方法级开启，gate/route/insert 同锁原子。 */
    @Transactional
    public DecideResult decide(long jobId, String triggerType) {
        JobInfo job = jobInfoMapper.selectByIdForUpdate(jobId);
        if (job == null) {
            return null;
        }
        return decideUnderLock(job, triggerType);
    }
```
  - 语义核对：decide 行为零变化（同一行锁读 + 同序 gate→route→insert，判空 guard 仍在 wrapper）；类上新增 `import com.wwjob.core.util.CronUtil;`（claimLocked 用，com.wwjob.core.util 包）。
  - **Ruling（对 spec §2 推荐结构的自觉偏离）**：spec 建议合并入口挂 JobTriggerServiceImpl（因它"当前 claim 属主"），但本库既有模式是 JobDecisionService 持全部决策 mappers（jobInfoMapper/jobLogMapper/routerService 已注入）且 `trigger()`→`decide()` 即跨 bean 开事务。故 claimLocked/decideCron 落 JobDecisionService（self-contained，少在 JobTriggerServiceImpl 加依赖），JobTriggerServiceImpl 只保留无事务 `triggerCronFast` 经 proxy 调 decideCron —— 与 spec 结构等价、事务边界一致。若 review 认为必须贴 spec 原文放 JobTriggerServiceImpl，可平移（代价是 JobTriggerServiceImpl 需新增 jobInfoMapper 之外的依赖 + decideCron 开在 triggerCronFast 上），本 plan 不采纳。

- [ ] **Step 3: `JobTriggerServiceImpl` 收敛**：
  - `claimNextTime`（66-81 行）方法体替换为委托（保留 @Transactional，保证 sharding claim 事务边界）：
```java
    @Transactional
    @Override
    public JobInfo claimNextTime(long jobId, String cron) {
        return jobDecisionService.claimLocked(jobId, cron);   // 锁下核心在 JobDecisionService（B1a 收敛）
    }
```
  - 删除原 83-94 行静态 `claimable`（已迁 JobDecisionService，本类不再使用）；
  - 删 import `com.wwjob.core.util.CronUtil`（第 9 行）—— claimNextTime（77 行）是其在 JobTriggerServiceImpl 的唯一使用点，委托后必删，否则 compile 报 unused 警告；
  - 类内 `DecideResult` import 保留（decide/trigger 仍用）。

- [ ] **Step 4: 编译**
```
mvn -q -pl ww-job-admin -am compile
```
Expected: BUILD SUCCESS。

- [ ] **Step 5: 行为零变化回归**（重启 admin 后）：
```
python tools/verify_p1_stage_a.py fast
python tools/verify_p1_stage_a.py slow
python tools/verify_p1_stage_a.py cron
```
Expected: 全 PASS（重构不得改变任何断言；若 c6 D2 50s 超时窗偶尔因环境慢，重跑 slow 一次）。

- [ ] **Step 6: Commit**（用户决定时机）
```
git add ww-job-admin/src/main/java/com/wwjob/admin/service/JobDecisionService.java
git add ww-job-admin/src/main/java/com/wwjob/admin/service/JobTriggerServiceImpl.java
git commit -m "refactor: B1a 锁下核心收敛（claimLocked/decideUnderLock + claimable 单一属主），行为零变化"
```

---

### Task 3: B1b 合并单事务（ice-ww 自研）

**Files:**
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/service/JobDecisionService.java`（+ `decideCron`）
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/service/JobTriggerService.java`（接口 + `triggerCronFast`）
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/service/JobTriggerServiceImpl.java`（实现 `triggerCronFast`）
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/schedule/ScheduleHelper.java`（环回调按 routeStrategy 分支）

**Interfaces:**
- Consumes: Task 2 的 `claimLocked`/`decideUnderLock`；Task 1 的 route 缓存
- Produces: `JobDecisionService.decideCron(long,String)`（@Transactional，非 sharding cron 决策单事务）、`JobTriggerService.triggerCronFast(long,String)`（决策后事务外 dispatch）；ScheduleHelper 改为对非 sharding 走合并入口。Task 4 探针断言此路径 = 5 往返/单 BEGIN。

- [ ] **Step 1: `JobDecisionService` 加合并入口 `decideCron`**：
```java
    /** cron 到期触发决策（非 sharding）：单事务内 锁行 → status/claimable 门 → advance → SINGLE gate → route → INSERT。
     *  返回 DecideResult 供事务外 dispatch；停用/边界未到/被阻塞/无执行器 → null（已落账或无需落账）。
     *  事务次序钉死：advance 在 gate 之前（B-1，blocked 也消费本边界）；HTTP 由调用方在事务外分发。 */
    @Transactional
    public DecideResult decideCron(long jobId, String cron) {
        JobInfo job = claimLocked(jobId, cron);
        if (job == null) {
            return null;
        }
        return decideUnderLock(job, "cron");
    }
```

- [ ] **Step 2: 接口 + 实现加 `triggerCronFast`**（本方法**不带 @Transactional** —— 事务由 decideCron 经 proxy 开启，返回后已提交再 dispatch）：
```java
    // JobTriggerService
    /** cron 到期（非 sharding 快路径）：claim+decide 合并单事务决策，事务提交后内部 dispatch（HTTP 在事务外）。 */
    void triggerCronFast(long jobId, String cron);
```
```java
    // JobTriggerServiceImpl
    @Override
    public void triggerCronFast(long jobId, String cron) {
        DecideResult result = jobDecisionService.decideCron(jobId, cron);   // 跨 bean @Transactional：合并决策单事务
        if (result != null) {
            dispatch(result, "cron");   // decideCron 返回即事务已提交，HTTP 在锁窗外
        }
    }
```

- [ ] **Step 3: `ScheduleHelper` 环回调按 routeStrategy 分支**（100-104 行，即 try 体内「注释 + claim + if」五行替换为；外层 `try {` 99 与 `} finally { scheduledJobIds.remove(...); }` 105-107 不动）：
```java
                // 快路径（非 sharding）：claim+decide 合并单事务（B1），决策返回即事务已提交，dispatch 在事务外；
                // sharding：维持 claim(独立事务)→broadcast 两段（父不变式 7，不进合并事务）
                if ("sharding".equalsIgnoreCase(job.getRouteStrategy())) {
                    JobInfo claimed = triggerService.claimNextTime(job.getId(), job.getCron());
                    if (claimed != null) {
                        triggerService.trigger(claimed, "cron");
                    }
                } else {
                    triggerService.triggerCronFast(job.getId(), job.getCron());
                }
```
  - `job` 闭包变量持有 scheduleLoop 每轮新鲜实体的 routeStrategy（编辑间短暂陈旧可接受，与 cron 编辑同量级；如需绝对新鲜可锁内分支，spec §2 已记二选一）。

- [ ] **Step 4: 编译**
```
mvn -q -pl ww-job-admin -am compile
```
Expected: BUILD SUCCESS。

- [ ] **Step 5: 回归 + B1 特有验证**（重启 admin 后）：
```
python tools/verify_p1_stage_a.py fast
python tools/verify_p1_stage_a.py slow
python tools/verify_p1_stage_a.py cron
```
  - **B-1 blocked 边界消费实锤**（SQL 断言）：建 SINGLE cron 任务（`*/1`），先手工插一条该 job running 行（status=0）制造重叠，观察下一次 cron 到期 → 落 status=4 阻塞行**且** job `trigger_next_time` 已推进（不等同秒永续重试）；随后跑完 running、再等下个边界 → 正常落 running/success。同秒双触发决定性 SQL（Task 4 也跑）此时应已为 0。
  - **F6-2 决定性 SQL**（单 admin 已由 verify cron 的 by_sec 断言兜底）在 Task 4 双 admin/高密度复跑。

- [ ] **Step 6: Commit**（用户决定时机）
```
git add ww-job-admin/src/main/java/com/wwjob/admin/service/JobDecisionService.java
git add ww-job-admin/src/main/java/com/wwjob/admin/service/JobTriggerService.java
git add ww-job-admin/src/main/java/com/wwjob/admin/service/JobTriggerServiceImpl.java
git add ww-job-admin/src/main/java/com/wwjob/admin/schedule/ScheduleHelper.java
git commit -m "feat: B1b claim+decide 合并单事务——cron 快路径决策 2tx→1tx（spec B-1 次序钉死）"
```

---

### Task 4: 验证收口（Claude 执行）

**Files:**
- Verify: `tools/probe_write_amp_cron.py`（Task 0 已建）
- Modify: `docs/load-test-results.md`（P12 注脚，默认不提交 git，改完留工作区由用户决定）
- Modify: `docs/superpowers/specs/2026-09-03-p1-write-amplification-design.md` §阶段B 或本 spec（实测记录回填）

**Interfaces:**
- Consumes: Task 0 红基线、Task 1-3 合入后的代码

- [x] **Step 1: cron 口径 GREEN**：`python tools/probe_write_amp_cron.py` → **PASS（core=5.0/触发、job_registry≈0、决策单 BEGIN…COMMIT）**，对照 Task 0 红（7/两段）达成 7→5。
- [x] **Step 2: 全量回归**：`verify_p1_stage_a.py fast/slow/cron` + SINGLE 并发 decisive + 手动/无执行器/stop 竞态/分片广播 e2e 全绿（T1-T3 各自 Step 回归 + B1 特有两个回归工具覆盖）。
- [x] **Step 3: B2 专项**（spec §6.3）：三工具全 PASS——
  - `tools/verify_b2_eviction.py`：kill 8081 → ≤90s 缓存/DB 同语义剔除 → 触发 status=2「无可用执行器」（handle_time NULL）；重启 executor → 恢复路由 status=1；
  - `tools/verify_b2_coldstart.py`：重启 admin → 首个心跳周期内立即触发 status=1（空缓存 DB 回退/水合，不误落 status=2）；
  - `tools/verify_b2_dualadmin.py`：双 admin（8080/8085）对称竞争 261 fires、(job_id, trigger 秒) 双触发=0、全 status=1（F6-2 决定性 SQL）。
- [x] **Step 4: D=300 A/B**：`tools/verify_b2_ab_p13.py` → **PASS（决定性复测）**：284.5/s vs P12 166.9/s（+70%）、per-sec min46/max399/0空秒、尾段 273.9/s、status 全1、distinct=3000、同秒双触发0、行锁 waits Δ=0、conns 恒31、DB往返/s=5×284.5≈1423。诚实标注：两轮实测 229.2（首轮）→284.5（决定性复测），Δ~55/s 落在饱和 run-to-run 方差带内；相对 Stage A 增益方向与量级（+37%~+70%）稳健。归档 load-test-results.md §Phase11。
- [x] **Step 5: P12 注脚**：`docs/load-test-results.md` P12 档加注脚（含总表 ⑤）：probe 手动口径 5.0 ≠ cron 实为 7 → 真实 ~1169 DB往返/s；Stage B 后 cron 口径 5。留工作区不提交。
- [x] **Step 6: 实测回填**：spec §9 实测记录表（probe/B2三专项/B1 blocked/A/B 六行）+ load-test-results.md §Phase11 归档。提交与否由用户决定。

---

## Self-Review 对照（spec 覆盖）

| spec 要求 | Task |
|---|---|
| §0 基线对账（cron=7，弃 5.0） | Task 0（红基线）+ Task 4 Step1（绿）+ Step5（注脚） |
| §1 红绿灯 1-7 + B-1 + B-2 | Task 2（core 迁移保 F6-2/锁窗）、Task 3 Step5（B-1 blocked 消费）、Task 1（B-2 回退）、Task 4 全量回归 |
| §2 B1 合并单事务（decideCron + 事务外 dispatch + sharding 不动 + 少丢火） | Task 2（core）+ Task 3（decideCron/triggerCronFast/ScheduleHelper 分支） |
| §3 B2 缓存（写穿 3 点 + 空集回退水合 + 字典序 + list 仍 DB + 90s/30s 事实） | Task 1 |
| §4 账目 7→5 / 2tx→1tx | Task 1 + 3（结构）+ Task 4 探针证明 |
| §6.1 回归 | Task 2/3 Step5 + Task 4 Step2/3 |
| §6.2 cron probe | Task 0 + Task 4 Step1 |
| §6.3 B2 专项 | Task 4 Step3 |
| §6.4 D=300 A/B | Task 4 Step4 |
| §7 文档修正 | Task 4 Step5 |
| §8 交付物 | 全 Task + Task 4 Step6 |
