# ww-job 失败告警（Failure Alert）设计

> 日期：2026-08-28
> 状态：已设计（待实现）
> 背景：任务失败散落在 5 处落 status=2/3，无人知晓；`job_info.alarm_config` 字段自建表起预留，一直未用。需要任务失败时自动通知运维。

---

## 1. 现状与问题

当前任务执行失败有 5 个落点，全都只是把 `job_log.status` 改成 2（失败）或 3（未知），没有任何通知机制：

| 落点 | 位置 | 状态 |
| --- | --- | --- |
| 回调失败（handler 明确返回失败） | `CallbackController.callback()` | status=2 |
| 投递失败 / 无可用执行器 | `JobTriggerServiceImpl.dispatch()/broadcast()` | status=2 |
| 投递超时（结果未知） | `JobTriggerServiceImpl.dispatch()` | status=3 |
| 巡检兜底（status=0 超阈值没回调） | `JobLogTimeoutScanner.scan()` | status=3 |
| SINGLE 被阻塞丢弃 | `JobDecisionService.decide()` | status=3（**不算失败**） |

**问题**：任务失败只有查 `job_log` 表才看得到，没有主动通知。运维依赖轮询，失败可能被忽略数小时。

**目标**：任务失败 → 自动发**邮件**告警到 `alarm_config` 配置的收件人。

---

## 2. 关键决策记录

| # | 决策 | 结论 |
| --- | --- | --- |
| D1 | 告警在哪触发 | **独立失败监控器 `JobFailMonitor`**（@Scheduled 定时扫 `job_log` 找新失败），**零侵入**现有 5 处落状态代码。理由：所有失败路径最终都落 `status=2/3`，扫描一网打尽；新失败路径自动被覆盖；与已有的 `JobLogTimeoutScanner` 巡检模式一致 |
| D2 | 哪些算失败该告警 | `status=2`（明确失败）全告警；`status=3` 区分：**超时/结果未知告警**，**SINGLE 被阻塞不告警**。判定用扫描窗口字段：`handle_time` 在最近窗口内 → 告警；被阻塞日志 `handle_time=null`（saveLog 惯例不设）→ **自动排除，无需字符串匹配** |
| D3 | 通知渠道 | `AlarmHandler` 接口（设计文档 6.7 预告的模式）+ **邮件实现**（`spring-boot-starter-mail` / `JavaMailSender`）。`alarm_config` 存**逗号分隔的收件人邮箱**。为空的任务不告警。本次只实现邮件，未来钉钉/企微 = 再加一个实现类 |
| D4 | 去重防轰炸 | **jobId 级聚合 + 时间窗口**：同一任务在窗口内（默认 10 分钟）的所有失败日志**聚合成一封**告警，且窗口内只发一次。广播 N 台全失败也只发 1 封（不刷屏），连续失败每隔一个窗口才补一封。`logId` 级无需单独处理——聚合天然覆盖 |
| D5 | 敏感配置 | SMTP 账号/授权码走 `application-local.yml`（gitignored），`application.yml` 只放 host/port 占位。遵循项目安全铁律，授权码绝不入库 |

---

## 3. 目标架构与数据流

```
任务失败（任一落点，status 变 2/3）
     │
     ▼
JobFailMonitor 每 30s 扫描（@Scheduled，与巡检一致）
     │  SELECT job_log WHERE status IN (2,3) AND handle_time >= now - 窗口
     ▼
按 jobId 分组（同一任务的多条失败日志 → 一组）
     ▼
对每个 jobId：
  1. 去重：该任务窗口内已告警过？→ 跳过（lastAlertByJobId 检查）
  2. 查关联 job_info：alarm_config 为空？→ 跳过（未订阅告警）
  3. 该组全部失败日志聚合成一封告警内容
     ▼
AlarmHandler.send(alarmConfig, title, content)
     ▼
MailAlarmHandler：解析收件人列表，逐封发送
     ▼
运维邮箱收到告警
```

**关键**：扫描窗口字段用 `handle_time`（失败落定时间）而不是 `trigger_time`。因为巡检超时的任务可能 15 分钟前触发、这轮才落 status=3——用 `trigger_time` 会漏掉；且被阻塞日志 `handle_time=null` 被自动排除。

---

## 4. 改动明细

### admin（全部改动在 admin，core / executor / schema 零改动）

**新建 `com.wwjob.admin.alarm` 包**：

1. **`AlarmHandler.java`**（接口）
   - `void send(String alarmConfig, String title, String content) throws Exception`
   - 渠道内聚：每个实现自己解析 `alarmConfig`（邮件 = 逗号分隔邮箱；未来钉钉 = webhook URL）
   - 本次唯一实现，`JobFailMonitor` 直接注入接口即可

2. **`MailAlarmHandler.java`**（`@Component`，邮件实现）
   - 注入 `JavaMailSender`（由 starter-mail 自动配置）
   - 解析 `alarmConfig.split(",")` → 逐收件人发 `SimpleMailMessage`
   - 发送失败 `throw Exception` 交给监控器记日志（不影响调度）

3. **`JobFailMonitor.java`**（`@Component`，核心）
   - `@Scheduled(fixedRate = 30000)`：扫新失败 → 按 jobId 分组 → 去重 → 查 alarm_config → 聚合组装 → 调 handler
   - 内存 `ConcurrentHashMap<Long, Long>`（jobId → 上次告警时间戳），窗口 10 分钟（与 SQL 窗口一致）
   - 同一 jobId 窗口内多条失败日志 → 一封邮件，逐条列出（见 §6 格式）
   - 告警发送失败只记日志，**绝不抛异常**（不能影响调度线程）

**改 `JobLogMapper.java`**：加自定义查询

```sql
SELECT l.* FROM job_log l
WHERE l.status IN (2, 3)
  AND l.handle_time >= #{from}
```

**改 `ww-job-admin/pom.xml`**：加 `spring-boot-starter-mail`

**改 `application.yml`**：加 mail 配置（host/port/username 占位 + 注释指向 local）

```
spring.mail.host, spring.mail.port, spring.mail.username, spring.mail.password
# password（SMTP 授权码）在 application-local.yml 覆盖，不入库
```

**`application-local.yml`**（gitignored，用户本机）：填真实 host/port/username/授权码

### samples（验证用）

**新建 `FailDemoHandler.java`**：`@JobHandler("failDemoHandler")`，execute 直接 `return ReturnT.fail("模拟业务失败");`——制造回调失败 status=2 用于端到端验证。

---

## 5. 边界（明确记录）

1. **未配置 `alarm_config`** → 不告警，静默跳过（告警是订阅制，不是强制的）
2. **SMTP 配置缺失 / 发送失败** → 监控器 catch 后记日志，**不影响调度线程与任务执行**
3. **SINGLE 被阻塞**（status=3 但 handle_time=null）→ 自动排除，不发告警（设计内的正常丢弃）
4. **告警不是实时的** → 最多延迟一个扫描周期（30s）。可接受，告警场景不需要秒级
5. **去重窗口内存态** → admin 重启后丢失，重启后该任务再失败会再告警一次。可接受
6. **巡检超时后被迟到回调覆盖**（先 3 后 1/2）→ 可能产生一次「超时告警」而实际后来成功了。这是结果未知的提醒而非误报，设计内接受
7. **邮件正文纯文本** → 用 `SimpleMailMessage`，不做 HTML 模板（YAGNI）

---

## 6. 告警内容格式

主题：`【ww-job 告警】任务 {jobName} 执行失败`

正文（单条失败日志）：

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

**同一任务窗口内多条失败日志**（如广播 3 台全失败）→ 一封邮件，正文按上格式逐条列出，每条以「— 日志 1 / 2 / 3 —」分隔。

---

## 7. 验证方案（端到端实测）

前置：`application-local.yml` 配好真实 SMTP（用户自己的邮箱服务商 + 授权码），`alarm_config` 填收件人邮箱。

| 场景 | 期望结果 |
| --- | --- |
| 建 `failDemoHandler` 任务 + `alarm_config`=自己邮箱，手动触发 | ✅ 回调 status=2 → 30s 内监控器扫描 → 收件箱收到告警邮件 |
| 连续多次触发同一失败任务 | ✅ 去重窗口内只收到 1 封告警（jobId 级去重） |
| 不填 `alarm_config` 的失败任务 | ✅ 不发告警 |
| 正常成功任务 | ✅ 不发告警（无新失败日志） |
| 配 SINGLE + 并发触发制造被阻塞日志 | ✅ 不发告警（handle_time=null 自动排除） |
| 停掉 executor 触发 | ✅ 投递失败 status=2 也告警 |

---

## 8. 非目标（本次不做）

- 其他渠道（钉钉/企微 webhook）——接口已留扩展位，本次只做邮件
- 失败恢复通知（失败后再成功的「已恢复」邮件）
- 告警历史记录表 / 前端告警列表
- 邮件 HTML 模板、多语言
- 分级告警（按任务重要程度分不同收件人策略——`alarm_config` 已天然支持每任务独立收件人）
