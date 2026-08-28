# 失败告警（Failure Alert）实现计划

> **For agentic workers:** 本计划由用户在 Claude 指导下自行实现（每任务由用户写代码，Claude 逐任务 review，不代写）。步骤用 checkbox（`- [ ]`）跟踪。

**Goal:** 任务失败（`job_log` 落 `status=2/3`）时自动发**邮件**告警到任务 `alarm_config` 配置的收件人，短时间去重防轰炸。

**Architecture:** 新增独立失败监控器 `JobFailMonitor`（@Scheduled 每 30s 扫 `job_log` 找新失败日志，按 jobId 聚合 + 10 分钟去重，查 `alarm_config` 有收件人就调 `AlarmHandler` 发邮件）。`AlarmHandler` 接口 + `MailAlarmHandler` 邮件实现（spring-boot-starter-mail / JavaMailSender）。现有 5 处落状态代码**零侵入**。

**Tech Stack:** Java 17 + Spring Boot 3.3 + MyBatis-Plus + MySQL 8（既有架构）+ `spring-boot-starter-mail`（新增依赖）。

**Spec:** `docs/superpowers/specs/2026-08-28-failure-alert-design.md`

## Global Constraints

- **零侵入**：现有落状态代码（CallbackController / JobTriggerServiceImpl / JobLogTimeoutScanner / JobDecisionService）**一行不动**
- 扫描条件用 `handle_time >= now - 10分钟`（不是 `trigger_time`）；被阻塞日志 `handle_time=null` 自动排除，**不做字符串匹配**
- 去重：同一 jobId 在 10 分钟窗口内只发**一封聚合邮件**；内存 `ConcurrentHashMap<Long, Long>`（jobId → 上次告警时间戳），重启丢失可接受
- `alarm_config` 为空的任务**不告警**（订阅制）
- 告警发送失败只记日志，**绝不抛异常**（不能影响调度线程）
- SMTP 授权码**只在 `application-local.yml`**（gitignored），不入库、不进聊天
- 中文注释，类头 `@author 王威 @version 1.0`（照项目惯例）
- MyBatis-Plus 注解 SQL（`@Select`），与 `JobLogMapper` 现有风格一致

---

### Task 1: 依赖 + 新查询 + 配置确认

**Files:**
- Modify: `ww-job-admin/pom.xml`（加 starter-mail 依赖）
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/mapper/JobLogMapper.java`（加新失败查询）
- （`application.yml` / `application-local.yml` 的 mail 配置用户已填，本任务确认存在即可，不用再改）

**Interfaces:**
- Produces: `JobLogMapper.selectRecentlyFailed(LocalDateTime from)` 返回 `List<JobLog>`

- [ ] **Step 1: pom 加依赖**
  在 `ww-job-admin/pom.xml` 的 `<dependencies>` 里加：
  ```xml
  <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-mail</artifactId>
  </dependency>
  ```
  （Spring Boot 3.3 parent 统一管理版本，不需要 `<version>`）
- [ ] **Step 2: JobLogMapper 加查询**
  加方法 + import `java.time.LocalDateTime`：
  ```java
  @Select("SELECT l.* FROM job_log l WHERE l.status IN (2, 3) AND l.handle_time >= #{from}")
  List<JobLog> selectRecentlyFailed(@Param("from") LocalDateTime from);
  ```
  （`@Param` 需 import `org.apache.ibatis.annotations.Param`，文件里已有）
- [ ] **Step 3: 确认 mail 配置在位**
  确认 `application.yml` 有 `spring.mail` 占位（host/port/ssl），`application-local.yml` 有 username/授权码。已填则跳过。
- [ ] **Step 4: 编译验证**
  `mvn -pl ww-job-admin -am compile` 通过。
- [ ] **Step 5: Commit**
  `git commit -m "feat: admin 加 spring-boot-starter-mail 依赖 + JobLogMapper 新失败查询"`

---

### Task 2: AlarmHandler 接口 + MailAlarmHandler 邮件渠道

**Files:**
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/alarm/AlarmHandler.java`
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/alarm/MailAlarmHandler.java`

**Interfaces:**
- Consumes: `spring.mail.*` 配置（Task 1 确认）
- Produces: `AlarmHandler.send(String alarmConfig, String title, String content) throws Exception`

- [ ] **Step 1: 写接口 `AlarmHandler`**
  ```java
  public interface AlarmHandler {
      /** 发送告警。alarmConfig 由各渠道自行解析（邮件 = 逗号分隔邮箱）。失败抛异常 */
      void send(String alarmConfig, String title, String content) throws Exception;
  }
  ```
  注释写清「渠道内聚：每个实现自己解析 alarmConfig」。
- [ ] **Step 2: 写 `MailAlarmHandler`（@Component 实现）**
  - 构造器注入 `JavaMailSender mailSender`
  - `send(String alarmConfig, String title, String content)`：
    1. `alarmConfig.split(",")` → 每个 trim 掉空白
    2. 对每个收件人：`new SimpleMailMessage()`，`setTo(邮箱)` / `setSubject(title)` / `setText(content)`，`mailSender.send(msg)`
    3. 收件人列表为空 → 直接 return（不抛）
  - 发送异常不 catch，向上抛（交给 JobFailMonitor 记日志）
  - 注意：`SimpleMailMessage` 是**邮件实现**，不 import 接口——接口在同一个包
- [ ] **Step 3: 编译**
  `mvn -pl ww-job-admin -am compile` 通过。
- [ ] **Step 4: Commit**
  `git commit -m "feat: AlarmHandler 接口 + MailAlarmHandler 邮件渠道"`

---

### Task 3: JobFailMonitor 失败监控器（核心）

**Files:**
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/alarm/JobFailMonitor.java`

**Interfaces:**
- Consumes: `JobLogMapper.selectRecentlyFailed(LocalDateTime)`（T1）、`JobInfoMapper.selectById(Long)`（BaseMapper 自带）、`AlarmHandler`（T2）
- Produces: `@Scheduled` 扫描任务，每 30s 自动跑

- [ ] **Step 1: 写类骨架**
  - `@Component`，构造器注入 `JobLogMapper`、`JobInfoMapper`、`AlarmHandler`
  - 常量 `private static final int WINDOW_MINUTES = 10;`
  - 去重表 `private final ConcurrentHashMap<Long, Long> lastAlertAt = new ConcurrentHashMap<>();`（jobId → 上次告警 epoch millis）
  - 私有 `Logger`（照项目现有类用 `org.slf4j.Logger/LoggerFactory`）
- [ ] **Step 2: 写 `scan()` 主逻辑**
  `@Scheduled(fixedRate = 30000)`，整体逻辑：
  ```
  try {
      from = LocalDateTime.now().minusMinutes(WINDOW_MINUTES);
      List<JobLog> failedLogs = jobLogMapper.selectRecentlyFailed(from);
      // 按 jobId 分组
      Map<Long, List<JobLog>> byJob = failedLogs.stream().collect(groupingBy(JobLog::getJobId));
      for (每个 (jobId, logs) 组) {
          now = System.currentTimeMillis();
          last = lastAlertAt.getOrDefault(jobId, 0L);
          if (now - last < WINDOW_MINUTES * 60_000) continue;   // 窗口内已告警过 → 跳过
          JobInfo job = jobInfoMapper.selectById(jobId);
          if (job == null || job.getAlarmConfig() == null || job.getAlarmConfig().isBlank()) continue;  // 未订阅 → 跳过
          String title = "【ww-job 告警】任务 " + job.getJobName() + " 执行失败";
          String content = buildContent(job, logs);            // 聚合所有日志 → 一封正文
          alarmHandler.send(job.getAlarmConfig(), title, content);
          lastAlertAt.put(jobId, now);                         // 只有发送成功才更新去重表（失败下次重试）
      }
  } catch (Exception e) {
      logger.error("失败告警扫描异常", e);   // 绝不向上抛，不能影响调度
  }
  ```
- [ ] **Step 3: 写 `buildContent(JobInfo job, List<JobLog> logs)`**
  按 spec §6 格式。单条：
  ```
  【ww-job 任务告警】
  任务：{jobName}（jobId={jobId}）
  执行器：{executorAddress}
  触发方式：{triggerType}
  失败时间：{handleTime}
  状态：{2=执行失败 / 3=超时，结果未知}
  失败原因：{handleMsg}
  日志ID：{logId}
  ```
  多条（`logs.size() > 1`）→ 每条前面加 `— 日志 {i}/{size} —` 分隔行，拼接成一个 String。
  （字符串拼接用 StringBuilder；`handleTime` 是 `LocalDateTime`，直接用 `toString()`）
- [ ] **Step 4: 编译**
  `mvn -pl ww-job-admin -am compile` 通过。
- [ ] **Step 5: 启动冒烟**
  以 local profile 启动 admin（`mvn -pl ww-job-admin -am spring-boot:run -Dspring-boot.run.profiles=local`），确认：
  - 启动无报错（starter-mail 已自动配置 JavaMailSender）
  - 日志出现 `JobFailMonitor` 的 `@Scheduled` 每 30s 无异常
- [ ] **Step 6: Commit**
  `git commit -m "feat: JobFailMonitor 失败监控器（扫描+聚合+去重+邮件告警）"`

---

### Task 4: FailDemoHandler 示例

**Files:**
- Create: `ww-job-executor-samples/src/main/java/com/wwjob/executor/samples/handler/FailDemoHandler.java`

**Interfaces:**
- Produces: `@JobHandler("failDemoHandler")`——制造回调失败 status=2，用于端到端验证

- [ ] **Step 1: 写 handler**
  照 `DemoHandler` 的结构：
  - `@JobHandler("failDemoHandler")`，`implements IJobHandler`
  - `execute(JobContext ctx)`：`return ReturnT.fail("模拟业务失败");`
  - import：`com.wwjob.core.context.JobContext`、`com.wwjob.core.handler.IJobHandler`、`com.wwjob.core.handler.JobHandler`、`com.wwjob.core.model.ReturnT`
- [ ] **Step 2: 编译**
  `mvn -pl ww-job-executor-samples -am compile` 通过。
- [ ] **Step 3: Commit**
  `git commit -m "feat: failDemoHandler 示例（制造失败用于告警验证）"`

---

### Task 5: 端到端验证（真实 SMTP）

**Files:**
- 不改代码；起服务、建任务、查库、看邮件

- [ ] **Step 1: 起服务**
  admin（local profile）+ 1 台 executor。确认注册中心在线。
- [ ] **Step 2: 建任务**
  `handlerName="failDemoHandler"`，`alarm_config` = **你自己的 QQ 邮箱**，cron 停用（手动触发）。
- [ ] **Step 3: 主场景——失败告警**
  手动触发 → executor 回调 status=2 → 等 ≤30s → 邮箱收到 1 封【ww-job 告警】邮件，正文含任务名/原因「模拟业务失败」/日志ID。
- [ ] **Step 4: 去重验证——连续失败只一封**
  再手动触发 2-3 次（每次回调 status=2）→ 去重窗口内**不再收到**新邮件（只 Step 3 那封）。
- [ ] **Step 5: 未订阅验证——无 alarm_config 不告警**
  再建一个 `failDemoHandler` 任务**不填** `alarm_config`，触发 → 无邮件、日志静默跳过。
- [ ] **Step 6: 正常任务不告警**
  触发一个成功任务 → 无邮件。
- [ ] **Step 7: 查询日志佐证**
  `job_log` 里两条失败日志（Step 3/5 各一条）`status=2`、`handle_time` 已落。

---

### Task 6: 文档标记已实现 + 收尾

**Files:**
- Modify: `docs/superpowers/specs/2026-08-28-failure-alert-design.md`

- [ ] **Step 1: spec 标记状态**
  顶部 `状态：已设计（待实现）` → `已实现并端到端验证通过（真实 SMTP 收件）`；§7 验证表实测行标 ✅。
- [ ] **Step 2: Commit**
  `git commit -m "feat: 失败告警端到端验证通过 + 文档标记已实现"`
- [ ] **Step 3: 推送**
  `git push origin main`（确认 `git status -sb` 无 ahead/behind）。
