# ww-job 多 admin 集群 + 分布式锁 设计

> 日期：2026-08-29
> 状态：设计已对齐，待实现
> 背景：调度中心当前为**单 admin**（每台 admin 独立内存时间轮 + 进程内去重）。部署多 admin 时，所有实例都从共享 DB 预读并各自触发，**同一 cron 任务会被执行多次**；失败告警的 `lastAlertAt` 内存去重也会失效（重复发邮件）。本次给调度中心补上**分布式互斥**，支持多 admin 同时运行。

---

## 1. 现状与问题

**调度链路（单 admin 成立，多 admin 失效）**：
1. `ScheduleHelper.scheduleThread` 每秒扫 DB（`trigger_status=1 AND trigger_next_time<=now+5s`）→ 预读进**本进程内存** `TimeWheel`
2. `scheduledJobIds`（进程内 Set）防同一次扫描重复入轮
3. `ringThread` 每秒推进时间轮 → 到期 `triggerService.trigger(jobId, "cron")`
4. 触发后 `advanceNextTime` 推进 `trigger_next_time`

**多 admin 的两个问题**：
- **P1 调度重复（必须解决）**：A、B 两台的 `scheduledJobIds`/`TimeWheel` 各自独立，都会预读同一任务、各自到点触发 → 同一触发点被执行两次。`JobDecisionService.decide()` 虽有行锁（`selectByIdForUpdate`）但**不检查 next_time 是否已推进**，挡不住两台先后触发。
- **P2 告警重复（必须解决）**：`JobFailMonitor.lastAlertAt` 是进程内 `ConcurrentHashMap`，A、B 各自去重 → 同一失败两封告警邮件。

**天然幂等、无需处理**：`JobLogTimeoutScanner`（第二台扫描时 status 已变 3，查不到）、`RegistryCleaner`（重复删幂等）。

---

## 2. 关键决策记录

| # | 决策点 | 结论 |
| --- | --- | --- |
| D1 | 互斥手段 | **保留自研时间轮**，在**触发入口**加分布式幂等——`SELECT job_info FOR UPDATE` 行锁 + `trigger_next_time` **前置推进**（先推进、再触发）。不用 xxl-job「抢全局锁 + DB 扫描即触发」模式（会废弃时间轮，丢失项目亮点） |
| D2 | 行锁粒度 | **每任务行锁**（`job_info.id`），优于全局锁：多 admin 可并行调度**不同**任务；同一任务同一触发点只被一台实际执行 |
| D3 | 幂等判定 | 抢到行锁后，若 `trigger_next_time > now`（已被别台推进）→ **跳过**；否则先推进 next_time 写回（标记本次触发点已分配）→ 提交释放锁 → **再**真正触发 |
| D4 | 手动触发 | `manual` **不走** claimNextTime（不推进 next_time），由现有 `decide()` 的 SINGLE 互斥兜底。多 admin 下重复手动触发属用户操作层面，非集群缺陷 |
| D5 | 告警去重 | `JobFailMonitor` **去掉内存 lastAlertAt**，改抢全局锁 `alert_lock`（`job_lock` 表 `FOR UPDATE`）+ 每任务 `last_alert_at` **落库**（`job_alert_state` 表）按 10min 窗口去重。多 admin 只有抢到锁者扫描，且去重状态共享 DB → 不重复发 |
| D6 | 全局锁实现 | 新建 `JobLockService.withLock(lockName, Runnable)`：`@Transactional` 方法内 `SELECT ... FOR UPDATE` 拿锁 → 执行 body → 事务提交释放锁。独立 Bean（`@Transactional` 需代理生效，不能自调用） |
| D7 | 不动 | `JobLogTimeoutScanner` / `RegistryCleaner` 幂等不加锁 |
| D8 | 不做 | executor 多 admin 故障切换（仍连单 admin，后续项）、Redis 锁、分片锁、告警历史页 |

---

## 3. 数据库变更（schema.sql 追加 2 表 + 2 条幂等 INSERT）

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

> schema 初始化 `mode: always` 每次启动执行，故 INSERT 必须幂等（`ON DUPLICATE KEY UPDATE` 空操作）。

---

## 4. 新增实体 / Mapper / Service

**实体**（风格照 `JobRegistry`，依赖 `map-underscore-to-camel-case`）：
- `entity/JobLock.java`：`@TableName("job_lock")`，主键 **String 非自增** → `@TableId(value = "lock_name", type = IdType.INPUT)`；属性 lockName、description、createTime、updateTime
- `entity/JobAlertState.java`：`@TableName("job_alert_state")`，主键 `@TableId(type = IdType.AUTO)`；属性 id、jobId、lastAlertAt、createTime、updateTime

**Mapper**：
- `mapper/JobLockMapper.java`：
  ```java
  @Select("SELECT * FROM job_lock WHERE lock_name = #{lockName} FOR UPDATE")
  JobLock selectForUpdate(@Param("lockName") String lockName);
  ```
- `mapper/JobAlertStateMapper.java`：`extends BaseMapper<JobAlertState>` 空接口（selectByJobId / insert / update 走 BaseMapper）

**Service**：
- `service/JobLockService.java`：
  ```java
  @Service
  public class JobLockService {
      private final JobLockMapper jobLockMapper;
      public JobLockService(JobLockMapper jobLockMapper) { this.jobLockMapper = jobLockMapper; }

      /** 事务内 SELECT ... FOR UPDATE 拿锁，body 执行完随事务提交释放锁（阻塞式互斥） */
      @Transactional
      public void withLock(String lockName, Runnable body) {
          if (jobLockMapper.selectForUpdate(lockName) == null) {
              throw new IllegalStateException("锁不存在: " + lockName + "，检查 schema 初始化");
          }
          body.run();
      }
  }
  ```

---

## 5. 调度幂等改造（核心，用户自研参考）

### 5.1 `JobTriggerService` 接口加方法 + 实现

`JobTriggerServiceImpl` 新增（注入已有 `jobInfoMapper`，复用 `CronUtil`）：
```java
/** 抢行锁 + 前置推进 next_time。返回 true 才允许触发（本次触发点未被别台分配） */
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

> **为什么先推进再触发**：A 触发（HTTP 最长 10s 读超时）期间，B 抢锁时看到 next_time 已推进到未来 → 返回 false 跳过。若先触发后推进，A 还没推进时 B 会重复触发。
>
> **为什么 @Transactional 独立 Bean**：`selectByIdForUpdate` 的行锁必须持有到 `updateById` 提交才算"原子抢占"；若放 `ScheduleHelper` 内自调用，`@Transactional` 代理不生效，锁在方法返回即释放，竞态失效。

### 5.2 `ScheduleHelper` 改造

`trigger` 任务 lambda 改为（**删除 `advanceNextTime`**，推进逻辑前移进 `claimNextTime`）：
```java
timeWheel.addTask(delay, () -> {
    try {
        if (triggerService.claimNextTime(job.getId(), job.getCron())) {
            triggerService.trigger(job.getId(), "cron");
        }
    } finally {
        scheduledJobIds.remove(job.getId());
    }
});
```

`scheduleIfNeeded` 的**落后追赶**逻辑保留（预读阶段 `next<now` 时推进到 `CronUtil.nextTime(cron, now)`，多 admin 推进幂等——值相同）。

---

## 6. 告警去重改造（用户自研参考）

`JobFailMonitor`：
1. **删掉** `private final ConcurrentHashMap<Long, Long> lastAlertAt`（内存去重失效）
2. 注入 `JobLockService` + `JobAlertStateMapper`
3. `scan()` 改为：
```java
@Scheduled(fixedRate = 30000)
public void scan() {
    try {
        jobLockService.withLock("alert_lock", this::scanLocked);  // 全局锁：同一时刻只有一台 admin 在扫告警
    } catch (Exception e) {
        log.error("失败告警扫描异常", e);
    }
}

private void scanLocked() {
    LocalDateTime from = LocalDateTime.now().minusMinutes(WINDOW_MINUTES);
    List<JobLog> failed = jobLogMapper.selectRecentlyFailed(from);
    Map<Long, List<JobLog>> byJob = failed.stream().collect(Collectors.groupingBy(JobLog::getJobId));
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
        alarmHandler.send(job.getAlarmConfig(), "【ww-job 告警】 任务" + job.getJobName() + "执行失败", buildContent(job, logs));
        upsertAlertState(jobId, now);  // 发送成功才记 last_alert_at
    }
}

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
```

> 全部逻辑在 `scanLocked`（`withLock` 的事务内）：只有抢到 `alert_lock` 的 admin 执行，且去重状态存共享 DB → 多 admin 不重复发；10min 防轰炸语义保留。

---

## 7. 联调与启动（双 admin 验证）

1. **同库两实例**：admin(8080, local) + admin(8081, local，`--server.port=8081`) + executor(监听 8081，admin 地址仍配 8080) + `npm run dev`。
   - 8081 实例启动命令（覆盖端口）：
     ```bash
     mvn -pl ww-job-admin -am spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments=--server.port=8081
     ```
2. executor 只连 8080（默认配置），但 `job_registry` 在共享 DB，8081 同样能读到在线执行器。

---

## 8. 验证方案（端到端，双 admin）

| # | 场景 | 预期 |
| --- | --- | --- |
| 1 | 两台 admin 启动后，各 `curl /jobgroup/list` / `/dashboard/stats` 正常 | 双实例健康，都连同一 DB |
| 2 | 建 cron `0/5 * * * * ?` 任务（demoHandler），跑 30s | `job_log` 中**每 5s 恰好一条**（约 6 条，无重复），`trigger_last_time` 正常推进 |
| 3 | 同时停一台 admin | 另一台继续调度不中断（cron 任务仍每 5s 一条） |
| 4 | 手动触发 `failDemoHandler` 任务（可配 alarm_config） | 只收到**一封**告警邮件（而非两封），`job_alert_state` 落该 jobId 的 last_alert_at |
| 5 | 10min 内再次失败 | 不发重复告警（DB 去重窗口生效） |
| 6 | 手动触发 demoHandler | 正常执行一次（不受 claimNextTime 影响） |

### 实测记录

（待实现后回填）

---

## 9. 边界与已知局限（明确记录）

1. **executor 仍连单 admin**：executor 心跳/注册只发一个 admin 地址；该 admin 宕机后 executor 不自动切换（xxl-job 的 admin 地址列表 + 失败切换为后续项）。但调度/注册数据在共享 DB，另一台 admin 仍能正常调度。
2. **手动触发无跨实例幂等**：用户对同一任务连点两次会触发两次（单 admin 亦然），属操作层面，非集群缺陷。
3. **claimNextTime 只拦截 cron 触发**：`sharding` 广播走 `trigger()`（broadcast）不经 decide，但广播入口在 claimNextTime 之后，仍受同一触发点互斥保护。
4. **时间轮多实例重复预读**：预读无副作用，只是多台各自入轮；实际触发由 claimNextTime 幂等拦截，浪费极少量内存/CPU，可接受。

---

## 10. 非目标（本次不做）

- executor 多 admin 地址列表 + 故障切换
- Redis 分布式锁（项目定位"自研不依赖外部组件"，Redis 仅备用）
- 告警历史页 / 告警记录落库（仍只发邮件）
- 分片锁 / 更细粒度锁、Quartz/一致性哈希等高级路由
