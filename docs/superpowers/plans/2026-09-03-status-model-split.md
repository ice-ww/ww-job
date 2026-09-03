# item5 状态模型拆分(STATUS_BLOCKED=4)+ 告警审计 · 实现计划

> **For agentic workers:** 本计划由 Claude 全权执行(2026-09-03 授权,同 item2);任务用 `- [ ]` 追踪。
> 协作:后端常规由 ice-ww 自研,但 item5 已明确「全权给 Claude」,含实现/验证/提交。

**Goal:** 把「被阻塞丢弃」从 status=3(超时未知)中拆出为独立 status=4,并让未订阅告警的失败任务每 admin 进程每 job 落一条可查 WARN。

**Architecture:** 只新增一个状态常量 + 单写点改判(JobDecisionService.decide);超时未知(dispatch / TimeoutScanner)零改动;读者侧 Dashboard 加第 7 卡、前端 LOG_STATUS 加 4;存量历史行一次性 migrate;JobFailMonitor 加实例级 `Set<Long>` 去重审计。

**Tech Stack:** Java 17 / Spring Boot 3.3.5 / MyBatis-Plus / MySQL / Vue3 + Element Plus(admin 侧无 Lombok,手写 getter/setter)。

**Spec:** `docs/superpowers/specs/2026-09-03-status-model-split-design.md`

## Global Constraints

- **status 常量在 `JobLog` 实体**:加 `STATUS_BLOCKED = 4`;仓库无 Lombok。
- **超时未知保持 status=3**:dispatch 超时(:171-181)与 `JobLogTimeoutScanner`(:33)都不许动。
- **blocked 写点唯一**:只 `JobDecisionService.decide` 阻塞分支写 4;handle_msg 固定串 `'任务上一次执行尚未结束，本次触发被阻塞丢弃'`、handle_time 不设。
- **selectRecentlyFailed 谓词不变** `IN (2,3) AND handle_time >= #{from}`:blocked(handle_time=null)天然不入告警,只加方法上方 Java 注释。
- **迁移等值匹配**:`UPDATE ... WHERE status=3 AND handle_msg='任务上一次执行尚未结束，本次触发被阻塞丢弃'`,绝不误伤超时未知行。
- **WARN 有界**:`Set<Long> auditedNoAlarm = ConcurrentHashMap.newKeySet()`(每 admin 进程每 job 一条;重启可再记)。
- **真密码红线**:DB 凭据只从 gitignored `application-local.yml` 读(tools 用 `registry_unique_key_migration.read_local_password()`);聊天/文档/commit 不出现任何真实密码。
- **文件全用绝对路径**;提交仅在任务步骤内按计划进行(用户已授权本 item 提交)。
- **3 个用户文件永不触碰/提交**:`docs/superpowers/plans/2026-08-29-*.md`、`docs/superpowers/plans/2026-08-30-*.md`、`ww-job-executor/**/controller/JobController.java`。

---

### Task 1: 后端模型——JobLog 加 STATUS_BLOCKED

**Files:**
- Modify: `D:\javacode\ww-job\ww-job-admin\src\main\java\com\wwjob\admin\entity\JobLog.java:15-19, 276-278`

- [ ] **Step 1: 类头 javadoc + 常量**
  把:
  ```java
  /** 日志状态：0 运行中，1 成功，2 失败，3 未知（超时/被阻塞，结果不确定） */
  public static final int STATUS_RUNNING = 0;
  public static final int STATUS_SUCCESS = 1;
  public static final int STATUS_FAIL = 2;
  public static final int STATUS_UNKNOWN = 3;
  ```
  改为:
  ```java
  /** 日志状态：0 运行中，1 成功，2 失败，3 未知（超时，结果不确定），4 被阻塞（重叠丢弃，未执行） */
  public static final int STATUS_RUNNING = 0;
  public static final int STATUS_SUCCESS = 1;
  public static final int STATUS_FAIL = 2;
  public static final int STATUS_UNKNOWN = 3;
  public static final int STATUS_BLOCKED = 4;   // 被阻塞(重叠丢弃，未执行)：与 STATUS_UNKNOWN 区分
  ```
- [ ] **Step 2: toString() 常量前缀补 4**
  在 `"STATUS_UNKNOWN = " + STATUS_UNKNOWN + ", "` 后插 `"STATUS_BLOCKED = " + STATUS_BLOCKED + ", "`。

- [ ] **Step 3: schema.sql 注释**(仅新库,旧库靠 migrate)
  `D:\javacode\ww-job\ww-job-admin\src\main\resources\db\schema.sql:56`
  `3未知(超时/被阻塞，结果不确定)` → `3未知(超时，结果不确定) 4被阻塞(重叠丢弃，未执行)`。

- [ ] **Step 4: 编译**
  Bash(引号防 PS 拆参):`cd /d/javacode/ww-job && mvn -pl ww-job-admin -am compile -q`
  期望:BUILD SUCCESS。

### Task 2: 后端写点——decide 阻塞分支落 4 + 告警 SQL 钉语义

**Files:**
- Modify: `D:\javacode\ww-job\ww-job-admin\src\main\java\com\wwjob\admin\service\JobDecisionService.java:39-41`
- Modify: `D:\javacode\ww-job\ww-job-admin\src\main\java\com\wwjob\admin\mapper\JobLogMapper.java:32`

- [ ] **Step 1: decide 改判**
  把:
  ```java
              // 上一次执行尚未结束：丢弃本次触发（记"被阻塞"日志，不制造重复执行）
              saveLog(job, triggerType, "任务上一次执行尚未结束，本次触发被阻塞丢弃", JobLog.STATUS_UNKNOWN);
  ```
  改为:
  ```java
              // 上一次执行尚未结束：丢弃本次触发。独立 STATUS_BLOCKED=4——被丢弃≠超时未知：不入告警/巡检，Dashboard 单独可见
              saveLog(job, triggerType, "任务上一次执行尚未结束，本次触发被阻塞丢弃", JobLog.STATUS_BLOCKED);
  ```
  handle_msg 字符串逐字保留(spec A6 migrate 等值匹配靠它)。

- [ ] **Step 2: selectRecentlyFailed 上方加注释**
  `@Select("SELECT l.* FROM job_log l WHERE l.status IN (2, 3) AND l.handle_time >= #{from}")` 方法上方加:
  ```java
      // 2失败 3超时未知；4被阻塞(handle_time=null)不入告警——谓词故意只收 2/3，语义见 spec A3
  ```
  谓词本身一个字不改。

- [ ] **Step 3: 编译**(同上命令,BUILD SUCCESS)。

### Task 3: 后端 Dashboard——Stats DTO + 计数

**Files:**
- Modify: `D:\javacode\ww-job\ww-job-admin\src\main\java\com\wwjob\admin\dto\DashboardStats.java`
- Modify: `D:\javacode\ww-job\ww-job-admin\src\main\java\com\wwjob\admin\service\DashboardService.java:45`

- [ ] **Step 1: DTO 加字段/访问器**
  - `private long logUnknownToday;` 行下加 `private long logBlockedToday;`
  - 仿 logUnknownToday 手写 `getLogBlockedToday()` / `setLogBlockedToday(long)`(带类内一致的 `/** 获取 @return ... */` 注释)
  - `toString()` 的 `logUnknownToday = " + logUnknownToday + ", "` 后插 `"logBlockedToday = " + logBlockedToday + ", "`(保持手写风格)
  - 全参构造器(:26)不改(未用,新字段默认 0 即可)。

- [ ] **Step 2: service 计数**
  `s.setLogUnknownToday(...)` 下一行加:
  ```java
          s.setLogBlockedToday(logMapper.countByStatus(todayStart, JobLog.STATUS_BLOCKED));
  ```
- [ ] **Step 3: 编译**(BUILD SUCCESS)。

### Task 4: 前端——LOG_STATUS + Dashboard 卡

**Files:**
- Modify: `D:\javacode\ww-job\ww-job-web\src\constants.js:13-18`
- Modify: `D:\javacode\ww-job\ww-job-web\src\views\Dashboard.vue:15-22, 52`

- [ ] **Step 1: constants.js**
  LOG_STATUS 数组加:
  ```js
    { value: 4, label: '被阻塞', tag: 'info' },
  ```
  (JobLogList 的筛选项/标签/颜色全部由 LOG_STATUS.find 自动派生,无需改该组件;`?status=4` 路由 query 已消费。)

- [ ] **Step 2: Dashboard.vue cards 数组**
  `{ key: 'logUnknownToday', ... }` 行下加:
  ```js
    { key: 'logBlockedToday', label: '今日被阻塞', to: '/joblogs?status=4', color: '#909399' },
  ```
  注释可加 `// info 灰：丢弃，非故障`。

- [ ] **Step 3: span 4→3**(7 卡一行:24/3=8)
  `:span="4"` → `:span="3"`(模板 :52)。

- [ ] **Step 4: 前端自检(无需起 vite)**
  Grep 确认 constants.js 与 Dashboard.vue 无语法错位(引号/逗号配对);此步不启动 dev server(仓库无前端单测,集成验证靠 Task 8 curl + 后续手动)。

### Task 5: migrate SQL + tools 文案

**Files:**
- Create: `D:\javacode\ww-job\ww-job-admin\src\main\resources\db\migrate\2026-09-03-status-blocked.sql`
- Modify: `D:\javacode\ww-job\tools\trigger_concurrent.py:9,17`
- Modify: `D:\javacode\ww-job\tools\analyze_window.py:96`

- [ ] **Step 1: migrate 文件**
  ```sql
  -- 一次性迁移：历史「被阻塞丢弃」行从 status=3 迁到独立 status=4。
  -- 用 decide() 固定句柄等值匹配，绝不误伤超时未知(JobTriggerServiceImpl dispatch 超时)行。
  -- 执行：在 admin 停止写或低峰执行均可；先 SELECT 计数留档再 UPDATE（见下方参考）。
  UPDATE job_log SET status = 4
   WHERE status = 3 AND handle_msg = '任务上一次执行尚未结束，本次触发被阻塞丢弃';
  ```
- [ ] **Step 2: tools 文案**
  - trigger_concurrent.py:9「99 条 status=3 被阻塞」→「99 条 status=4 被阻塞」;:17「落 status=3」→「落 status=4」。
  - analyze_window.py:96「(0=执行中 1=成功 2=失败 3=超时/被阻塞)」→「(0=执行中 1=成功 2=失败 3=超时未知 4=被阻塞)」。
  - verify_timeout_boundary.py 不用改(它验证的是超时巡检翻 status=3,语义不变)。
  - phase7/8 历史留档脚本不回改。

### Task 6: 后端 Phase B——JobFailMonitor 告警审计

**Files:**
- Modify: `D:\javacode\ww-job\ww-job-admin\src\main\java\com\wwjob\admin\alarm\JobFailMonitor.java`

- [ ] **Step 1: import + 字段**
  `java.util.List` 上方加 `import java.util.Set;`;`java.util.Map` 后加 `import java.util.concurrent.ConcurrentHashMap;`。`WINDOW_MINUTES` 常量下加:
  ```java
      /** 未订阅告警的任务已 WARN 审计集合：每 admin 进程每 job 至多一条，防 30s 刷屏（重启可再记） */
      private final Set<Long> auditedNoAlarm = ConcurrentHashMap.newKeySet();
  ```
- [ ] **Step 2: scanLocked 拆 null 判 + 审计 WARN**
  把:
  ```java
              JobInfo job = jobInfoMapper.selectById(jobId);
              if (job == null || job.getAlarmConfig() == null || job.getAlarmConfig().isBlank()) {
                  continue;  // 未订阅告警
              }
  ```
  改为:
  ```java
              JobInfo job = jobInfoMapper.selectById(jobId);
              if (job == null) {
                  continue;  // 任务已删除：悬空引用非告警配置问题，不属本审计
              }
              if (job.getAlarmConfig() == null || job.getAlarmConfig().isBlank()) {
                  if (auditedNoAlarm.add(jobId)) {
                      log.warn("任务「{}」(id={}) 近 {}min 有 {} 次失败但未订阅告警，已跳过；如需告警请在任务配置 alarm_config",
                              job.getJobName(), jobId, WINDOW_MINUTES, logs.size());
                  }
                  continue;  // 未订阅告警：不发送，但每 admin 进程每 job 至少一条可查 WARN
              }
  ```
  10min 去重窗口跳过(:66-69)不动。
- [ ] **Step 3: 编译**(BUILD SUCCESS)。

### Task 7: 重启 admin 载入新代码

**前置**:当前 8080 跑 item2 代码,需换新。
- [ ] **Step 1: 停旧 admin**
  用 TaskStop/Bash 结束 8080 占用进程(javaw / spring-boot:run 子进程),确认 `curl -s localhost:8080/api/... ` 拒绝连接。
- [ ] **Step 2: 起新 admin**(Bash,引号防拆参)
  `cd /d/javacode/ww-job && mvn -pl ww-job-admin spring-boot:run '-Dspring-boot.run.profiles=local'` 后台;轮询 `/api/dashboard/stats` 或启动日志到 UP。
  (executor 无需起:blocked/审计场景均不依赖 executor。)

### Task 8: 验证 Phase A——被阻塞落 4 + Dashboard + migrate

**Files(工具,若新建):** `D:\javacode\ww-job\tools\verify_status_split.py`(留档回归工具,写后提交)

- [ ] **Step 1: e2e 被阻塞写 4(无 executor,countRunning 命中即丢弃)**
  - 选一个 `block_strategy='SINGLE'` 且 `trigger_status=1`(或临时)的 job(id=X);合成插一条 status=0 running 行(job_id=X, trigger_time=now, handle_msg='verify-synthetic')。
  - 调用 `POST /api/job/{X}/trigger`(manual)一次;查最新 job_log:status=**4** 且 handle_msg 含「被阻塞丢弃」且 handle_time IS NULL。
  - 清理合成行与 blocked 行(或留一条供 Dashboard 断言,验后清理)。
- [ ] **Step 2: Dashboard 双卡对照**
  `GET /api/dashboard/stats`:断言出现 `logBlockedToday` 字段;对照 `logUnknownToday` 不含 blocked 数(先记 unknown 基数,插一条 blocked 后 unknown 不变、blocked +1)。
- [ ] **Step 3: migrate 验证 + 真实留档**
  - 真实存量留档:SELECT count(*) `job_log WHERE status=3 AND handle_msg='任务上一次执行尚未结束，本次触发被阻塞丢弃'`(dev 3306)——通常为压测遗留;记录数字。
  - 跑 migrate SQL;复查 count=0;对照 `status=3 AND handle_msg<>'...'`(纯超时未知)行数不变。
- [ ] **Step 4: 超时路径不回归**
  复用 `tools/verify_timeout_boundary.py`:合成陈旧 running 行 → 仍翻 status=3(超时语义没被动到)。
- [ ] **Step 5: Phase B WARN 审计**
  - 选未订阅告警且停用的 job;插入一条 status=2、handle_time=now 的失败行。
  - 等一个扫描周期(≤~35s),admin 日志出现 WARN「未订阅告警」含该 jobId;再插第二条同 job 失败行,再等一周期——WARN 不重复(有界)。
  - 清理合成失败行。
- [ ] **Step 6: 回归**
  - 正常成功任务仍落 status=1;明确失败仍落 status=2(可跑一条快任务 e2e 或靠已有日志断言);前端 JobLogList 筛选项含「被阻塞」。
- [ ] **Step 7: 提交(用户已授权本 item)**
  `git add` 后端 5 文件 + 前端 2 + migrate + tools 2(+新建 verify 工具)后,`git commit -m "feat: item5 状态模型拆分——STATUS_BLOCKED=4 写点/告警/Dashboard/迁移 + 告警审计(每进程每job WARN)"`。

### Task 9: 收尾——spec 实测回填 + README + 内存

- [ ] **Step 1: spec 实测记录回填**
  `docs/superpowers/specs/2026-09-03-status-model-split-design.md` 「实测记录」段写:被阻塞 e2e 落 4 的日志 id、Dashboard 双卡数值对照、migrate 迁移行数、WARN 有界验证、verify_timeout_boundary 全绿;交付物清单勾选;状态行改「已实现并实测」。
- [ ] **Step 2: robustness-priorities 标记 item5 已修复**(留档,不回改历史数字)
- [ ] **Step 3: 提交 docs**(docs 常规默认不提交,但本 item 全权授权,spec 留档属惯例;提交信息 `docs: item5 标记已修复 + 实测记录回填`)
- [ ] **Step 4: 更新内存** `ww-job-project.md`:item5 完成(commit hash),下一步= P1 Stage A / item6。
