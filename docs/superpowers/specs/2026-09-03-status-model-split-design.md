# 状态模型拆分(STATUS_BLOCKED=4)+ 告警跳过可见化 · 设计 spec(item 5)

> 日期:2026-09-03 ｜ 状态:**已实现并实测(commit 0c0718c + 003e8a6/5f5568a 文档)** ｜ 前置:`docs/2026-09-03-robustness-priorities.md` §一-5(排序重议第 3 项)
> 协作模式:本工作流用户 ice-ww **授权 Claude 全权实现**(2026-09-03 会话对 item2/item5 两次「全权动手」，例外于 [[user-participation-preference]])。spec/plan/实现/回归由 Claude 完成。

## 背景与目标

job_log.status 把两种**语义完全不同**的终态都记成 `3`:
- **A 被阻塞**:`block_strategy=SINGLE` 下重叠触发被丢弃——任务**根本没执行**,operator 无需动作;
- **B 超时未知**:dispatch 已投递但读超时,执行器**可能仍在执行**——operator 需要查执行器日志判断,且"勿重复触发"。

混在同一个 status 里,Dashboard「今日未知」被 drop 数污染、日志筛选把两种"要不要人工介入"混为一谈。拆分后两者各得其所:**被阻塞 = 安全丢弃,独立可见,绝不告警**;超时未知维持原语义(告警范围、巡检范围不变)。

同文件附带第二个可观测性补丁(Phase B):`JobFailMonitor` 对"有失败但未订阅告警"的任务静默 `continue`,压测曾出现 1385 次失败零告警且日志无可查。补一条**有界审计** WARN。

**目标**:
1. 新状态 `STATUS_BLOCKED=4` 只覆盖"被阻塞丢弃"写点;Dashboard/筛选/工具能区分;超时未知语义零变化。
2. 存量历史 blocked 行(status=3)一次性迁到 4,历史报表诚实。
3. 未订阅告警的失败任务,每个 admin 启动周期每 job 至少一条 WARN 审计,不刷屏。

## 现状核对(Claude 对过源码,非转述)

| 点 | 现状 | 证据 |
|---|---|---|
| blocked 与超时共写 status=3 | `JobDecisionService.decide`:40 阻塞 → `saveLog(..., STATUS_UNKNOWN)`(handle_msg 固定串,handle_time **null**);`JobTriggerServiceImpl.dispatch`:172 超时 → `STATUS_UNKNOWN`(handle_time **有值**) | JobDecisionService.java:35-42;JobTriggerServiceImpl.java:171-181 |
| Dashboard「未知」混计 | `DashboardService.stats`:45 `logUnknownToday = countByStatus(todayStart, STATUS_UNKNOWN)` | DashboardService.java:42-46 |
| 告警已排除 blocked(靠 handle_time null) | `JobLogMapper.selectRecentlyFailed`:`status IN (2,3) AND handle_time >= #{from}`——blocked 的 handle_time=null 天然不命中 | JobLogMapper.java:32;JobFailMonitor.java:59 |
| 巡检只翻 status=0 | `JobLogTimeoutScanner.scan` 只 `.eq("status", ...0...)` 上行由 timeout SQL `WHERE l.status=0` 限定 | JobLogTimeoutScanner.java / JobLogMapper.java:21-30 |
| 回调无条件覆盖(无状态守卫) | `CallbackController.callback` selectById 后直接 updateById | CallbackController.java:27-39 |
| 前端 status 枚举 | `LOG_STATUS` = 0运行中/1成功/2失败/3未知;JobLogList 筛选项与标签颜色由 `LOG_STATUS.find` 派生 | ww-job-web/src/constants.js:13-18;JobLogList.vue:17,21-24 |
| Dashboard 卡布局 | 6 卡 `el-col :span="4"`(6×4=24 恰一行) | Dashboard.vue:15-22,52 |
| tools 图例把 3 当「超时/被阻塞」 | `analyze_window.py`:96 图例;`trigger_concurrent.py` 头注释「99 条 status=3 被阻塞」 | tools/*.py |
| 无 completeById 窄更新守卫 | 见 CallbackController 行(守卫属 P1 Stage A 范围) | P1 spec §A5/A6 |

## 决策记录(2026-09-03 与 ice-ww 敲定,四项全按推荐)

- **Q1 Dashboard 呈现**:新增第 7 卡「今日被阻塞」(后端 `logBlockedToday` + 前端卡,点跳 `/joblogs?status=4`);未知卡回归纯净。
- **Q2 存量迁移**:一次性 `UPDATE ... WHERE status=3 AND handle_msg='任务上一次执行尚未结束，本次触发被阻塞丢弃'`(**等值**匹配 decide 固定句柄,不误伤 timeout 行)。
- **Q3 告警审计节流**:未订阅告警跳过时,每 admin 启动每 job 记一次 WARN(实例级 `Set<Long>`,`ConcurrentHashMap.newKeySet()`);10min 告警去重窗口跳过不记。
- **Q4 spec 粒度**:一份 spec 两阶段——Phase A 状态拆分(先落地,须先于 P1 Stage A 敲定状态模型);Phase B 告警审计(独立,与状态拆分无耦合)。

## 正确性不变式(红绿灯)

1. **blocked 永不告警**:新写 blocked 行 status=4 且 handle_time=null → `selectRecentlyFailed IN(2,3)+handle_time>=from` 天然排除;SQL 加注释钉住语义。
2. **未知只含超时**:Dashboard `logUnknownToday`=status=3 只含 dispatch 超时未知;被阻塞单独计 `logBlockedToday`=status=4。
3. **写点唯一**:只有 `decide()` 阻塞分支写 4;dispatch 超时仍写 3。两态 handle_msg 文案与 handle_time 可空性互斥(迁移识别与回归断言据此)。
4. **迁移只命中 decide 句柄**:等值串匹配,绝不误伤超时未知行。
5. **审计有界**:未订阅告警的失败任务,每 admin 进程每 job 至多一条 WARN(重启后可再记)。
6. **不破坏现有**:status 0/1/2/3 的全部读者语义零变化;P1 Stage A 将来把回调收账守卫收窄为 `IN(0,3)` 时,blocked=4 天然在外,不需返工。

## Phase A — STATUS_BLOCKED=4

### A1 模型
**JobLog 实体**(status 常量区)加:
```java
public static final int STATUS_BLOCKED = 4;   // 被阻塞(重叠丢弃，未执行)：与 STATUS_UNKNOWN 区分
```
类头 javadoc 一并更新:`3 未知（超时/被阻塞）` → `3 未知（超时，结果不确定） 4 被阻塞（重叠丢弃，未执行）`。
`toString()` 的常量前缀列表补 `STATUS_BLOCKED`(保持一致)。

**schema.sql** job_log status 列注释(仅新库生效):
```sql
status TINYINT DEFAULT 0 COMMENT '0运行中 1成功 2失败 3未知(超时，结果不确定) 4被阻塞(重叠丢弃，未执行)',
```

### A2 写点(仅一处改动)
**JobDecisionService.decide** 阻塞分支改落 4(注释同步说清语义):
```java
boolean single = "SINGLE".equalsIgnoreCase(job.getBlockStrategy());
if (single) {
    long running = jobLogMapper.countRunning(jobId);
    if (running > 0) {
        // 上一次执行尚未结束：丢弃本次触发。独立 STATUS_BLOCKED=4——被丢弃≠超时未知：不入告警/巡检，Dashboard 单独可见
        saveLog(job, triggerType, "任务上一次执行尚未结束，本次触发被阻塞丢弃", JobLog.STATUS_BLOCKED);
        return null;
    }
}
```
dispatch 超时未知(dispatch:171-174)保持 status=3,不改。

### A3 读者语义表(大多不动,加注释钉语义)
| 读者 | 动作 |
|---|---|
| JobLogTimeoutScanner / JobLogMapper timeout SQL | 不动(只翻 status=0) |
| JobLogMapper.selectRecentlyFailed | 方法上加 Java 注释钉语义 `// 2失败 3超时未知；4被阻塞(handle_time=null)不入告警`(注解单行 SQL 不便内嵌 `--`,放方法上方);谓词不变 |
| CallbackController | 不动(D2 收账守卫属 P1 Stage A;blocked 行无执行器,永不收到回调) |
| DashboardService | 见 A4 |
| JobLog 前端筛选/标签 | 见 A5 |

### A4 Dashboard:新增「被阻塞」卡 + 未知卡自动纯净
**DashboardStats** DTO 加字段(手工 getter/setter,仓库无 Lombok):`private long logBlockedToday;`
**DashboardService.stats()** 加:
```java
s.setLogBlockedToday(logMapper.countByStatus(todayStart, JobLog.STATUS_BLOCKED));
```
`logUnknownToday` 不动——拆分后自动只剩超时未知。

**Dashboard.vue** cards 数组加第 7 张;el-col span 4→3 让 7 卡一行排下(24/3=8 格):
```js
const cards = [
  { key: 'jobTotal', label: '任务总数', to: '/jobs', sub: () => `启用 ${fmt(stats.value?.jobEnabled)} · 停用 ${fmt(stats.value?.jobDisabled)}` },
  { key: 'executorOnline', label: '在线执行器', to: '/registries', sub: () => `共 ${fmt(stats.value?.executorTotal)} 台注册` },
  { key: 'logTotalToday', label: '今日触发', to: '/joblogs' },
  { key: 'logSuccessToday', label: '今日成功', to: '/joblogs?status=1', color: '#67c23a' },
  { key: 'logFailToday', label: '今日失败', to: '/joblogs?status=2', color: '#f56c6c' },
  { key: 'logUnknownToday', label: '今日未知', to: '/joblogs?status=3', color: '#e6a23c' },
  { key: 'logBlockedToday', label: '今日被阻塞', to: '/joblogs?status=4', color: '#909399' },  // info 灰：丢弃，非故障
]
```
```html
<el-col v-for="c in cards" :key="c.key" :span="3">
```

### A5 前端 status 枚举
**constants.js** LOG_STATUS 加一行(JobLogList 筛选项/标签/颜色由 `find` 自动派生,无需改组件):
```js
{ value: 4, label: '被阻塞', tag: 'info' },
```

### A6 存量迁移(一次性)
**db/migrate/2026-09-03-status-blocked.sql**(沿用 registry 迁移先例:spring sql init 只认 schema.sql,此文件仅留档手动跑):
```sql
-- 一次性迁移：历史「被阻塞丢弃」行从 status=3 迁到独立 status=4。
-- 用 decide() 固定句柄等值匹配，绝不误伤超时未知(dispatch:171-181)行。
UPDATE job_log SET status = 4
 WHERE status = 3 AND handle_msg = '任务上一次执行尚未结束，本次触发被阻塞丢弃';
```
对 dev 3306 `ww_job` 执行(先 SELECT 计数留档再 UPDATE);loadtest 3307 容器如仍在也跑一次。

### A7 tools 文案(留档一致性;历史数字不回改)
- `tools/trigger_concurrent.py` 头注释/docstring:「99 条 status=3 被阻塞」→「99 条 status=4 被阻塞」;相关打印同步。
- `tools/analyze_window.py`:96 图例 `(0=执行中 1=成功 2=失败 3=超时未知 4=被阻塞)`。
- 历史 loadtest 留档数字(Phase5 SINGLE「33→0」等)为过去事实,不回改;拆分后重跑被阻塞计为 4。

## Phase B — 告警跳过可见化(有界审计)

**JobFailMonitor** 字段加实例级去重集(admin 进程生命周期,重启可再记):
```java
/** 未订阅告警的任务已 WARN 审计集合：每 admin 进程每 job 至多一条，防 30s 刷屏 */
private final Set<Long> auditedNoAlarm = ConcurrentHashMap.newKeySet();
```
scanLocked 的「未订阅告警」跳过分支改造(原 :71-73 静默 `continue`):
```java
JobInfo job = jobInfoMapper.selectById(jobId);
if (job == null) {
    continue;
}
if (job.getAlarmConfig() == null || job.getAlarmConfig().isBlank()) {
    if (auditedNoAlarm.add(jobId)) {
        log.warn("任务「{}」(id={}) 近 {}min 有 {} 次失败但未订阅告警，已跳过；如需告警请在任务配置 alarm_config",
                job.getJobName(), jobId, WINDOW_MINUTES, logs.size());
    }
    continue;  // 未订阅告警：不发送，但至少落一条可查审计
}
```
10min 告警去重窗口跳过分支(:66-69,`st != null && now - st.getLastAlertAt() < WINDOW` )不动——那是正常去重,不审计。补 import `java.util.Set` / `java.util.concurrent.ConcurrentHashMap`(或全限定)。

## 非目标(本 spec 不做)

- 回调收账的 completeById 状态守卫 `IN(0,3)`——属 P1 Stage A(A5/A6),本 spec 只保证 blocked=4 将来天然在守卫外,不提前实现。
- 状态模型整体推翻成 enum/字符串、跨 core/executor 铺开(admin 内 int 常量足够,YAGNI)。
- 告警对 status=3「超时未知」是否该告警的语义重议(维持现状:告警)。
- job==null(悬空 job_log 引用已删任务)的审计——数据异常非告警配置问题,不在本次范围。
- 回改历史 loadtest 留档数字。
- Dashboard 被阻塞卡的样式精细化(颜色/文案取 Element info 灰,可后调)。

## 交付物与验收清单

**Phase A**
- [x] JobLog 实体 `STATUS_BLOCKED=4` + toString 常量补全
- [x] schema.sql status 注释含 4
- [x] JobDecisionService.decide 阻塞分支落 STATUS_BLOCKED
- [x] DashboardStats.logBlockedToday + DashboardService 计数
- [x] Dashboard.vue 第 7 卡 + span 3
- [x] constants.js LOG_STATUS +4
- [x] migrate SQL(手动执行,dev SELECT 留档→UPDATE→复查)
- [x] tools 文案(trigger_concurrent / analyze_window)

**Phase B**
- [x] JobFailMonitor 未订阅告警每进程每 job WARN 一次

**验证(Claude 执行,dev 3306 单 admin 8080)**
- [x] 编译 `mvn -pl ww-job-admin -am compile` 绿
- [x] e2e 被阻塞:SINGLE 任务 + 合成 status=0 running 行 → 手动 trigger → 新 job_log **status=4** + handle_msg「被阻塞丢弃」(无需 executor,countRunning 命中即丢弃)
- [x] e2e 对照:dispatch 超时路径仍落 status=3(代码未动;超时行为由 item2 工具覆盖)
- [x] Dashboard curl:stats 含 `logBlockedToday`>0;unknown 数不含 blocked(两卡数值对照)
- [x] 迁移:插一条 status=3+decide 句柄的伪造行 → 跑 migrate UPDATE → 变 4(验后清理);历史真实行 SELECT 计数留档
- [x] Phase B:未订阅告警 job 造 status=2 失败行 → 一个扫描周期内 admin 日志出现 WARN「未订阅告警」;同 job 两条失败行只记一条(有界)
- [x] 回归:正常任务 status=1 / 失败 2 / 超时 3 语义不回归;前端筛选 status=4 可选

## 实测记录(2026-09-03,实现后回填)

单 admin(local, 3306 ww_job, 8080)回归,全部由 `tools/verify_status_split.py`(新增,C1/C2/D)与 `tools/verify_timeout_boundary.py`(超时回归)断言:

- **C1 blocked 写点**:载体 job_id=9(未订阅告警,临时置 SINGLE/停用)插入合成 running 行 id=16766(二跑 16770)→ 手动 trigger → 新 blocked 日志 16767/16771:`status=4`、`handle_msg=任务上一次执行尚未结束，本次触发被阻塞丢弃`、`handle_time=NULL`。Dashboard `logBlockedToday` 0→1;`logUnknownToday` 0→0(不被污染)。
- **migrate(真实存量)**:dev `status=3 AND handle_msg=decide句柄` 实有 **101 行**(历史压测遗留被阻塞,此前错记为「未知」)→ 跑 `migrate/2026-09-03-status-blocked.sql` 全迁 **status=4**;对照 `status=3` 超时未知行 2 条不动。C2 用合成 legacy 行 + 超时对照行复验隔离(decide 句柄等值匹配不误伤)。
- **Phase B 告警审计**:未订阅告警 fail 行(status=2, handle_time=now)→ 一个扫描周期内 admin 日志出现一次 WARN「近 10min 有 1 次失败但未订阅告警,已跳过」;同 job 再插第二条失败行等一周期 → WARN 仍只有 1 条(`auditedNoAlarm` 有界,每进程每 job)。
- **超时不回归**:`verify_timeout_boundary` 全绿——陈旧 running 行仍被巡检翻 **status=3**;时区去耦 A1(旧 NOW 谓词随时区翻转)/A2(新参数谓词稳定)不回退。
- 代码提交 `0c0718c`;spec/plan 提交 `003e8a6`/`5f5568a`。tools 文案同步(trigger_concurrent「99 条 status=4」/analyze_window 图例含 4);历史 loadtest 留档数字未回改。

## 参考

- 排序重议与 item5 详情:`docs/2026-09-03-robustness-priorities.md` §一-5、§二-修正3、§三-序3
- 状态相关压测留档:`docs/load-test-report.md`(Phase5 SINGLE 以 status=3 计数,拆分后重跑计 4)、`docs/load-test-results.md`
- 既有迁移先例:`ww-job-admin/src/main/resources/db/migrate/2026-09-03-registry-unique-key.sql`
- P1 spec(回调守卫依赖本状态模型):`docs/superpowers/specs/2026-09-03-p1-write-amplification-design.md`
