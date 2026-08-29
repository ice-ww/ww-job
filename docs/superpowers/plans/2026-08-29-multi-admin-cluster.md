# 多 admin 集群 + 分布式锁 实现计划

> **For agentic workers:** 本计划**由用户（ice-ww）自研后端代码**，Claude 提供参考实现与指导，不代写。任务按「参考代码 → 编译自测 → 提交」逐个推进；Claude 在每任务后 review 指导，T8/T9 共同验证。

**Goal:** 给调度中心补上分布式互斥，支持多 admin 实例同时运行（同一 cron 触发点只执行一次、失败告警不重复发）。

**Architecture:** 保留自研时间轮；cron 触发入口加 `claimNextTime()`（`SELECT job_info FOR UPDATE` 行锁 + `trigger_next_time` 前置推进）做幂等；告警扫描抢 `job_lock.alert_lock` 全局锁 + `job_alert_state` 表每任务 `last_alert_at` 落库去重。

**Tech Stack:** Java 17 + Spring Boot 3.3 + MyBatis-Plus（`@Select` 注解 SQL + BaseMapper）+ MySQL 8。

**Spec:** `docs/superpowers/specs/2026-08-29-multi-admin-cluster-design.md`

## Global Constraints

- schema 初始化 `mode: always` 每次启动执行 → 新表 **INSERT 必须幂等**（`ON DUPLICATE KEY UPDATE` 空操作）
- **@Transactional 锁逻辑必须在独立 Bean**（`JobLockService` / `JobTriggerServiceImpl`），不能在 `ScheduleHelper`/`JobFailMonitor` 内自调用（自调用不走 Spring 代理，事务不生效、行锁方法返回即释放）
- `JobLock` 主键是 **String 非自增** → `@TableId(value = "lock_name", type = IdType.INPUT)`
- 实体风格照 `JobRegistry`，依赖全局 `map-underscore-to-camel-case`，无需 `@TableField`
- **真实 MySQL 密码 / SMTP 授权码在 gitignored `application-local.yml`**，任何代码 / 文档 / commit message 不得出现真实密码
- admin 一律以 local profile 启动（`-Dspring-boot.run.profiles=local`）
- 字段名照表：`lock_name` / `description` / `job_id` / `last_alert_at`（驼峰 `lockName` / `lastAlertAt`）

---

### Task 1: schema.sql 追加分布式锁与告警状态表

**Files:**
- Modify: `D:\javacode\ww-job\ww-job-admin\src\main\resources\db\schema.sql`（末尾，job_log 表后）

**Produces:** `job_lock`（含 `alert_lock` 行）、`job_alert_state` 两张表，admin 重启自动建。

- [ ] **Step 1: 追加建表 SQL**

在 `schema.sql` 末尾追加：

```sql
CREATE TABLE IF NOT EXISTS job_lock (
    lock_name VARCHAR(64) PRIMARY KEY COMMENT '锁名',
    description VARCHAR(128) COMMENT '锁用途说明',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT '分布式锁';

INSERT INTO job_lock (lock_name, description) VALUES ('alert_lock', '失败告警扫描互斥锁')
    ON DUPLICATE KEY UPDATE lock_name = lock_name;

CREATE TABLE IF NOT EXISTS job_alert_state (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL COMMENT '任务id',
    last_alert_at BIGINT NOT NULL DEFAULT 0 COMMENT '上次告警毫秒时间戳（10min 去重窗口）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_job (job_id)
) COMMENT '任务告警去重状态';
```

> `INSERT ... ON DUPLICATE KEY UPDATE lock_name = lock_name` 是空操作：schema 每次启动都执行，第二次起 INSERT 撞主键自动忽略，保证幂等。

- [x] **Step 2: 自测**

重启 admin（先停旧的）：
```bash
mvn -pl ww-job-admin -am spring-boot:run -Dspring-boot.run.profiles=local
```
MySQL 里确认两表已建、`job_lock` 有 `alert_lock` 行：
```sql
SHOW TABLES;
SELECT * FROM job_lock;
```

- [x] **Step 3: Commit**（建议信息）

```bash
git add ww-job-admin/src/main/resources/db/schema.sql
git commit -m "feat: 新增 job_lock / job_alert_state 表（分布式锁 + 告警去重）"
```

---

### Task 2: 新增实体 JobLock + JobAlertState

**Files:**
- Create: `D:\javacode\ww-job\ww-job-admin\src\main\java\com\wwjob\admin\entity\JobLock.java`
- Create: `D:\javacode\ww-job\ww-job-admin\src\main\java\com\wwjob\admin\entity\JobAlertState.java`

**Consumes:** T1 的两张表。
**Produces:** 两个实体类，T3 的 Mapper、T4 的 JobLockService、T7 的 JobFailMonitor 依赖。

- [x] **Step 1: JobLock.java**（照 `JobRegistry` 风格；主键 String 非自增）

```java
package com.wwjob.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * @author 王威
 * @version 1.0
 */
@TableName("job_lock")
public class JobLock {
    /** 锁名，String 主键（非自增） */
    @TableId(value = "lock_name", type = IdType.INPUT)
    private String lockName;
    private String description;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public JobLock() {
    }

    public String getLockName() { return lockName; }
    public void setLockName(String lockName) { this.lockName = lockName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
```

- [x] **Step 2: JobAlertState.java**（照 `JobRegistry` 风格；主键自增）

```java
package com.wwjob.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * @author 王威
 * @version 1.0
 */
@TableName("job_alert_state")
public class JobAlertState {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long jobId;
    /** 上次告警毫秒时间戳（10min 去重窗口） */
    private Long lastAlertAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public JobAlertState() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }

    public Long getLastAlertAt() { return lastAlertAt; }
    public void setLastAlertAt(Long lastAlertAt) { this.lastAlertAt = lastAlertAt; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
```

- [x] **Step 3: 编译自测**

```bash
mvn -pl ww-job-admin -am compile -q
```
Expected: BUILD SUCCESS。

- [x] **Step 4: Commit**

```bash
git add ww-job-admin/src/main/java/com/wwjob/admin/entity/JobLock.java ww-job-admin/src/main/java/com/wwjob/admin/entity/JobAlertState.java
git commit -m "feat: JobLock / JobAlertState 实体"
```

---

### Task 3: 新增 Mapper JobLockMapper + JobAlertStateMapper

**Files:**
- Create: `D:\javacode\ww-job\ww-job-admin\src\main\java\com\wwjob\admin\mapper\JobLockMapper.java`
- Create: `D:\javacode\ww-job\ww-job-admin\src\main\java\com\wwjob\admin\mapper\JobAlertStateMapper.java`

**Consumes:** T2 实体。
**Produces:** `selectForUpdate(lockName)`（行锁拿锁）、`JobAlertStateMapper`（BaseMapper，`selectOne`/`insert`/`updateById` 走内置方法）。T4 / T7 依赖。

- [x] **Step 1: JobLockMapper.java**（照 `JobInfoMapper.selectByIdForUpdate` 的行锁写法）

```java
package com.wwjob.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wwjob.admin.entity.JobLock;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * @author 王威
 * @version 1.0
 */
public interface JobLockMapper extends BaseMapper<JobLock> {
    /** 分布式锁：FOR UPDATE 拿锁，事务提交才释放（阻塞式互斥）。必须在事务内使用 */
    @Select("SELECT * FROM job_lock WHERE lock_name = #{lockName} FOR UPDATE")
    JobLock selectForUpdate(@Param("lockName") String lockName);
}
```

- [x] **Step 2: JobAlertStateMapper.java**

```java
package com.wwjob.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wwjob.admin.entity.JobAlertState;

/**
 * @author 王威
 * @version 1.0
 */
public interface JobAlertStateMapper extends BaseMapper<JobAlertState> {
}
```

- [x] **Step 3: 编译自测**（同 T2，`mvn -pl ww-job-admin -am compile -q` → SUCCESS）

- [x] **Step 4: Commit**

```bash
git add ww-job-admin/src/main/java/com/wwjob/admin/mapper/JobLockMapper.java ww-job-admin/src/main/java/com/wwjob/admin/mapper/JobAlertStateMapper.java
git commit -m "feat: JobLockMapper（selectForUpdate 行锁）+ JobAlertStateMapper"
```

---

### Task 4: JobLockService.withLock 全局锁

**Files:**

- Create: `D:\javacode\ww-job\ww-job-admin\src\main\java\com\wwjob\admin\service\JobLockService.java`

**Consumes:** T3 `JobLockMapper`.
**Produces:** `withLock(String lockName, Runnable body)` —— T7 告警扫描用它串行化多 admin。

- [x] **Step 1: JobLockService.java**

```java
package com.wwjob.admin.service;

import com.wwjob.admin.mapper.JobLockMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author 王威
 * @version 1.0
 */
@Service
public class JobLockService {
    private final JobLockMapper jobLockMapper;

    public JobLockService(JobLockMapper jobLockMapper) {
        this.jobLockMapper = jobLockMapper;
    }

    /** 事务内 SELECT ... FOR UPDATE 拿锁，body 执行完随事务提交释放锁（阻塞式互斥）。
     *  锁行不存在时抛异常，避免"无锁执行"静默发生（多 admin 会重复） */
    @Transactional
    public void withLock(String lockName, Runnable body) {
        if (jobLockMapper.selectForUpdate(lockName) == null) {
            throw new IllegalStateException("锁不存在: " + lockName + "，检查 schema 初始化");
        }
        body.run();
    }
}
```

- [x] **Step 2: 编译自测**（`mvn -pl ww-job-admin -am compile -q` → SUCCESS）

- [x] **Step 3: Commit**

```bash
git add ww-job-admin/src/main/java/com/wwjob/admin/service/JobLockService.java
git commit -m "feat: JobLockService.withLock 分布式锁（事务内 FOR UPDATE）"
```

---

### Task 5: JobTriggerService 新增 claimNextTime（调度幂等核心）

**Files:**
- Modify: `D:\javacode\ww-job\ww-job-admin\src\main\java\com\wwjob\admin\service\JobTriggerService.java`
- Modify: `D:\javacode\ww-job\ww-job-admin\src\main\java\com\wwjob\admin\service\JobTriggerServiceImpl.java`

**Consumes:** 现有 `jobInfoMapper`（`selectByIdForUpdate` / `updateById`）、`CronUtil`.
**Produces:** `boolean claimNextTime(long jobId, String cron)` —— T6 ScheduleHelper 触发前调用。

- [x] **Step 1: 接口加方法**

`JobTriggerService.java` 接口内追加：

```java
    /**
     * 抢行锁 + 前置推进 next_time：返回 true 才允许触发。
     * 多 admin 下同一触发点只有一台会返回 true（先推进者），其余跳过。
     */
    boolean claimNextTime(long jobId, String cron);
```

- [x] **Step 2: 实现类加方法 + import**

`JobTriggerServiceImpl.java` 加 import `com.wwjob.core.util.CronUtil;`（`JobInfo`/`JobInfoMapper` 已 import），并在类内追加：

```java
    /** 触发点幂等：行锁内判断本次触发点是否已被别台分配，未分配则先推进 next_time 再放行 */
    @Transactional
    @Override
    public boolean claimNextTime(long jobId, String cron) {
        JobInfo job = jobInfoMapper.selectByIdForUpdate(jobId);
        if (job == null || job.getTriggerStatus() == null || job.getTriggerStatus() != 1) {
            return false;  // 任务不存在或已停用
        }
        long now = System.currentTimeMillis();
        Long lastNext = job.getTriggerNextTime();
        if (lastNext == null || lastNext > now) {
            return false;  // 已被别台 admin 推进，本次触发点已分配，跳过
        }
        long next = CronUtil.nextTime(cron, now);
        job.setTriggerNextTime(next);   // 先推进再触发：消除"触发中窗口"的重复
        jobInfoMapper.updateById(job);
        return true;
    }
```

> 语义：行锁持有到事务提交（`@Transactional` 独立 Bean 代理生效）。A 先推进提交后，B 拿锁看到 `next_time` 已在未来 → 返回 false 跳过。手动触发不经过此方法（由 `decide()` 的 SINGLE 互斥兜底）。

- [x] **Step 3: 编译自测**（`mvn -pl ww-job-admin -am compile -q` → SUCCESS）

- [x] **Step 4: Commit**

```bash
git add ww-job-admin/src/main/java/com/wwjob/admin/service/JobTriggerService.java ww-job-admin/src/main/java/com/wwjob/admin/service/JobTriggerServiceImpl.java
git commit -m "feat: claimNextTime 触发点行锁幂等（先推进 next_time 再触发）"
```

---

### Task 6: ScheduleHelper 触发入口接入 claimNextTime

**Files:**

- Modify: `D:\javacode\ww-job\ww-job-admin\src\main\java\com\wwjob\admin\schedule\ScheduleHelper.java`（94-104 行 trigger lambda、107-119 行 advanceNextTime 删除）

**Consumes:** T5 `claimNextTime`.
**Produces:** 触发链「claimNextTime → 才 trigger」，单 admin 行为不变，多 admin 幂等。

- [x] **Step 1: 改 trigger lambda + 删 advanceNextTime**

`scheduleIfNeeded` 内 `timeWheel.addTask` 的 lambda 改为（**删掉 `advanceNextTime(job.getId(), job.getCron());` 这一行**）：

```java
        long delay = Math.max(0, next - now);
        timeWheel.addTask(delay, () -> {
            try {
                // 触发点幂等：行锁内先推进 next_time（标记本次已分配），返回 true 才真正触发
                if (triggerService.claimNextTime(job.getId(), job.getCron())) {
                    triggerService.trigger(job.getId(), "cron");
                }
            } finally {
                scheduledJobIds.remove(job.getId());
            }
        });
```

同时**删除整个 `advanceNextTime` 方法**（推进逻辑已前移到 `claimNextTime`）。`scheduleIfNeeded` 里的落后追赶逻辑（`next < now` 分支）保留不动。

- [x] **Step 2: 编译自测**（`mvn -pl ww-job-admin -am compile -q` → SUCCESS；确认无 advanceNextTime 残留引用）

- [x] **Step 3: Commit**

```bash
git add ww-job-admin/src/main/java/com/wwjob/admin/schedule/ScheduleHelper.java
git commit -m "refactor: 触发入口接入 claimNextTime，删除 advanceNextTime"
```

---

### Task 7: JobFailMonitor 告警去重落库

**Files:**
- Modify: `D:\javacode\ww-job\ww-job-admin\src\main\java\com\wwjob\admin\alarm\JobFailMonitor.java`

**Consumes:** T2 `JobAlertState`、T3 `JobAlertStateMapper`、T4 `withLock`.
**Produces:** 多 admin 下同一失败只发一封告警；10min 防轰炸窗口由内存改为 DB。

- [x] **Step 1: 全文改造为下列内容**

```java
package com.wwjob.admin.alarm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wwjob.admin.entity.JobAlertState;
import com.wwjob.admin.entity.JobInfo;
import com.wwjob.admin.entity.JobLog;
import com.wwjob.admin.mapper.JobAlertStateMapper;
import com.wwjob.admin.mapper.JobInfoMapper;
import com.wwjob.admin.mapper.JobLogMapper;
import com.wwjob.admin.service.JobLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author 王威
 * @version 1.0
 */
@Component
public class JobFailMonitor {
    private static final Logger log = LoggerFactory.getLogger(JobFailMonitor.class);
    private static final int WINDOW_MINUTES = 10;

    private final JobLogMapper jobLogMapper;
    private final JobInfoMapper jobInfoMapper;
    private final JobAlertStateMapper alertStateMapper;
    private final AlarmHandler alarmHandler;
    private final JobLockService jobLockService;

    public JobFailMonitor(JobLogMapper jobLogMapper, JobInfoMapper jobInfoMapper,
                          JobAlertStateMapper alertStateMapper, AlarmHandler alarmHandler,
                          JobLockService jobLockService) {
        this.jobLogMapper = jobLogMapper;
        this.jobInfoMapper = jobInfoMapper;
        this.alertStateMapper = alertStateMapper;
        this.alarmHandler = alarmHandler;
        this.jobLockService = jobLockService;
    }

    @Scheduled(fixedRate = 30000)
    public void scan() {
        try {
            // 全局锁：同一时刻只有一台 admin 在扫告警；去重状态在共享 DB，多 admin 不重复发
            jobLockService.withLock("alert_lock", this::scanLocked);
        } catch (Exception e) {
            log.error("失败告警扫描异常", e);
        }
    }

    private void scanLocked() {
        LocalDateTime from = LocalDateTime.now().minusMinutes(WINDOW_MINUTES);
        List<JobLog> failed = jobLogMapper.selectRecentlyFailed(from);
        Map<Long, List<JobLog>> byJob = failed.stream()
                .collect(Collectors.groupingBy(JobLog::getJobId));
        long now = System.currentTimeMillis();
        for (Map.Entry<Long, List<JobLog>> entry : byJob.entrySet()) {
            long jobId = entry.getKey();
            List<JobLog> logs = entry.getValue();
            JobAlertState st = alertStateMapper.selectOne(new QueryWrapper<JobAlertState>().eq("job_id", jobId));
            if (st != null && now - st.getLastAlertAt() < WINDOW_MINUTES * 60_000) {
                continue;  // DB 去重窗口：上次告警 10min 内跳过
            }
            JobInfo job = jobInfoMapper.selectById(jobId);
            if (job == null || job.getAlarmConfig() == null || job.getAlarmConfig().isBlank()) {
                continue;  // 未订阅告警
            }
            String title = "【ww-job 告警】 任务" + job.getJobName() + "执行失败";
            try {
                alarmHandler.send(job.getAlarmConfig(), title, buildContent(job, logs));
            } catch (Exception e) {
                // 发送失败：整批回滚（不 upsert），下次扫描重试。至少一次语义，宁可重复不丢
                throw new RuntimeException("告警发送失败 jobId=" + jobId, e);
            }
            upsertAlertState(jobId, now);  // 发送成功才记 last_alert_at
        }
    }

    /** 发送成功后落库去重时间戳（存在则更新，不存在则插入） */
    private void upsertAlertState(long jobId, long now) {
        JobAlertState st = alertStateMapper.selectOne(new QueryWrapper<JobAlertState>().eq("job_id", jobId));
        if (st == null) {
            st = new JobAlertState();
            st.setJobId(jobId);
            st.setLastAlertAt(now);
            alertStateMapper.insert(st);
        } else {
            st.setLastAlertAt(now);
            alertStateMapper.updateById(st);
        }
    }

    private String buildContent(JobInfo job, List<JobLog> logs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < logs.size(); i++) {
            JobLog log = logs.get(i);
            if (logs.size() > 1) sb.append("— 日志 ").append(i + 1).append("/").append(logs.size()).append(" —\n");
            sb.append("【ww-job 任务告警】\n");
            sb.append("任务：").append(job.getJobName()).append("（jobId=").append(job.getId()).append("）\n");
            sb.append("执行器：").append(log.getExecutorAddress()).append("\n");
            sb.append("触发方式：").append(log.getTriggerType()).append("\n");
            sb.append("失败时间：").append(log.getHandleTime()).append("\n");
            sb.append("状态：").append(log.getStatus() == JobLog.STATUS_FAIL ? "执行失败" : "超时，结果未知").append("\n");
            sb.append("失败原因：").append(log.getHandleMsg()).append("\n");
            sb.append("日志ID：").append(log.getId()).append("\n");
            if (i < logs.size() - 1) sb.append("\n");
        }
        return sb.toString();
    }

}
```

> 关键点：删掉了内存 `lastAlertAt`；`scanLocked` 是 `Runnable`（method reference），不能声明 checked exception，故 `alarmHandler.send`（`throws Exception`）包成 `RuntimeException` 抛出 → 事务回滚 → `scan()` catch 记日志，下次重扫重试。

- [x] **Step 2: 编译自测**（`mvn -pl ww-job-admin -am compile -q` → SUCCESS）

- [x] **Step 3: Commit**

```bash
git add ww-job-admin/src/main/java/com/wwjob/admin/alarm/JobFailMonitor.java
git commit -m "feat: 告警去重落库（alert_lock 全局锁 + job_alert_state last_alert_at）"
```

---

### Task 8: 编译 + 单 admin 回归验证

**Files:** 无代码改动（验证）。可先 `git status` 确认工作区干净再开始。

- [x] **Step 1: 全量编译**

```bash
mvn clean compile -q
```
Expected: BUILD SUCCESS（四个模块）。

- [x] **Step 2: 起单 admin + executor + 前端**

三窗口：
```bash
mvn -pl ww-job-admin -am spring-boot:run -Dspring-boot.run.profiles=local
mvn -pl ww-job-executor-samples -am spring-boot:run
cd ww-job-web && npm run dev
```

- [x] **Step 3: cron 回归**（调度链未破坏）

建任务并等 ~35s：
```bash
curl -X POST http://localhost:8080/job -H "Content-Type: application/json" \
  -d '{"jobGroupId":1,"handlerName":"demoHandler","cron":"0/5 * * * * ?","routeStrategy":"round","retryCount":0,"blockStrategy":"SINGLE","triggerStatus":1}'
```
Expected: `GET /joblog/page?jobId=<新建id>&size=50` 每 5s 一条、约 6-7 条、全 SUCCESS、`trigger_time` 无同秒重复。

- [x] **Step 4: 手动触发回归**

```bash
curl -X POST http://localhost:8080/job/<id>/trigger
```
Expected: 正常执行一次（新日志 triggerType=manual）。

- [x] **Step 5: 告警回归（单 admin）**

给 failDemoHandler 任务配 `alarmConfig`（自己的邮箱），手动触发 → 执行失败：
- Expected: 收到**一封**告警邮件；`job_alert_state` 表出现该 jobId 行、`last_alert_at` 非 0
- 10min 窗口内再次触发失败 → **不**收到重复告警（DB 去重窗口生效）

> 若不想等 10min，可临时把 `WINDOW_MINUTES` 改成 1 测完改回——但注意提交时改回 10。

- [x] **Step 6: Commit**（T8 无代码变更，跳过；若期间有修 bug，单独提交）

---

### Task 9: 双 admin 端到端验证 + README + 推送

**Files:**
- Modify: `D:\javacode\ww-job\README.md`

- [ ] **Step 1: 起第二台 admin（8082，同库）**

> 注意：executor samples 监听 8081，第二台 admin 用 **8082**（避免端口冲突）。

保持 8080 admin + executor 运行，新窗口：
```bash
mvn -pl ww-job-admin -am spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments=--server.port=8082
```

- [ ] **Step 2: 双实例健康**

```bash
curl http://localhost:8080/jobgroup/list
curl http://localhost:8082/jobgroup/list
```
Expected: 两个都正常返回相同分组列表（同库）。

- [ ] **Step 3: 双 admin 下 cron 不重复**

8080 + 8081 同时运行，观察 **~60s**（覆盖至少 12 个触发点）：
```bash
curl "http://localhost:8080/joblog/page?jobId=<cron任务id>&size=50"
```
Expected: **每 5s 恰好一条**，`trigger_time` 严格递增、无任何同秒/相邻重复——证明同一触发点只有一台 admin 实际触发（claimNextTime 幂等生效）。

- [ ] **Step 4: 停一台，调度不中断**

Ctrl+C 停掉 8082，观察 8080 继续每 5s 一条（~15s 足够）。再重启 8082，同样无重复。

- [ ] **Step 5: 双 admin 告警不重复**

8080 + 8081 同时在跑，手动触发 failDemoHandler（alarmConfig 已配、10min 窗口已过）：
Expected: 只收到**一封**告警（而不是两封）。可再触发一次验证 DB 窗口去重。

- [ ] **Step 6: README 更新**

1. 技术栈表 `MySQL | 8 | ... 4 张表 ...` → **6 张表**（`job_lock` / `job_alert_state` 新增）
2. 数据模型表追加两行：
   | `job_lock` | 分布式锁 | lock_name(主键)、description |
   | `job_alert_state` | 任务告警去重 | job_id、last_alert_at |
3. 功能特性区加一条（与「自研时间轮调度器」并列）：
   `- **多 admin 集群**：cron 触发入口行锁幂等（同一触发点只执行一次）+ 失败告警全局锁去重，多实例可同时运行`
4. 调度链路第 6 步「并推进下次 triggerNextTime」补充说明：推进由触发前的 claimNextTime 完成（行锁幂等）
5. `Phase 1 范围与后续规划` 把 `- [ ] 调度中心集群（分布式锁）` 勾选为 `- [x]`

- [ ] **Step 7: 提交**

```bash
git add README.md
git commit -m "docs: 多 admin 集群已实现（分布式锁 + 触发幂等），README 更新"
```

- [ ] **Step 8: 推送**

```bash
git push -u origin main
```
（如遇网络问题参照以往：检查代理后重试。）

- [ ] **Step 9: 回填 spec 实测记录**

把 T8/T9 的实测结果（cron 条数、无重复、告警一封、job_alert_state 落库）回填到
`docs/superpowers/specs/2026-08-29-multi-admin-cluster-design.md` §8「实测记录」，再提交一次：
```bash
git add docs/superpowers/specs/2026-08-29-multi-admin-cluster-design.md
git commit -m "docs: 多 admin 集群端到端验证记录回填"
git push
```
