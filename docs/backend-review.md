# 后端设计审查记录（2026-08-26）

对 Phase 1 后端全量代码审查后整理的问题清单。分级：P0（会导致核心功能出错或调度瘫痪）、P1（特定条件下出错）、P2（健壮性/体验/设计缺口）。

状态图例：✅ 已修复 / 🔧 待修复 / 📋 待定

---

## 🔴 P0（已全部修复 ✅）

### P0-1 时间轮线程不安全
- 位置：`ww-job-core/.../schedule/TimeWheel.java` + `ScheduleHelper.java`
- 问题：`slots`/`currentTick` 无锁，`addTask()`（scheduleThread）与 `advance()`（ringThread）每秒并发读写，可能丢任务、重复触发、ConcurrentModificationException。
- 修复：`addTask` / `advance` 加 `synchronized`；task 执行在 advance() 外，不长期持锁。✅

### P0-2 触发无超时 + ringThread 阻塞
- 位置：`ScheduleHelper.java` + `JobTriggerServiceImpl.java` + `ExecutorRegistry.java`
- 问题：`new RestTemplate()` 默认连接/读超时无限；ringThread 直接在轮内同步做 HTTP → 一台执行器挂起可冻死整个调度器。
- 修复：
  - `JobTriggerServiceImpl` RestTemplate 设 connect 3s / read 10s；`ExecutorRegistry` 心跳 RestTemplate 同样设超时（@Scheduled 默认单线程，心跳挂起会停摆）。✅
  - `ScheduleHelper` 增加 `ww-job-trigger` 线程池（min 4，核心数×2），ringThread 只出队，触发执行交给线程池。✅

### P0-3 路由策略失效（round/failover 恒选第一个执行器）
- 位置：`ExecutorRouterService.java` + `RoundRobinRouter.java` / `FailoverRouter.java`
- 问题：`route()` 每次 `new` 一个路由器，内部 `AtomicInteger` 每次从 0 开始 → `idx` 恒为 0。
- 修复：路由实例提为 service 单例字段，计数器跨调用保持。✅
- 附带收益：修复后 failover 重试会自然轮转到不同执行器，部分缓解 P1-2。

---

## 🟠 P1（待处理 🔧）

### P1-1 注册中心 upsert 非原子，可能产生重复节点
- 位置：`RegistryService.java:41-56` + `schema.sql` job_registry 无唯一约束
- 问题：先 selectOne 再 insert/update，两步间无锁；两个并发心跳同时查出"不存在"→ 都 insert → 重复行。`onlineAddresses` 出现重复/陈旧地址。
- 修法：`UNIQUE KEY uk_group_value (job_group_id, registry_value)` + `INSERT ... ON DUPLICATE KEY UPDATE`。

### P1-2 failover 不是真正的故障转移
- 位置：`FailoverRouter.java` + `ExecutorRouterService.java` + `JobTriggerServiceImpl.java`
- 问题：FailoverRouter 不探测可用性；重试时失败的执行器 90s 内仍在列表，可能反复选中同一台死节点（P0-3 修复后已缓解为会轮转，但"剔除失败地址"仍未实现）。
- 修法：单次触发内维护失败地址排除集，重试时从候选剔除；或引入健康检查。

---

## 🟡 P2（待定 📋）

| # | 项 | 说明 |
| --- | --- | --- |
| P2-1 | `block_strategy`/`timeout`/`alarm_config` 字段建了未用 | schema 与实体暴露了未实现功能。**2026-08-26 思路一后 `block_strategy=SINGLE` 已实现**（见下）；**Phase 2 异步回调后 `timeout` 已被 `JobLogTimeoutScanner` 用作执行超时阈值（0=默认 60s）**，巡检超时标 status=3；`alarm_config` 仍无告警 |
| P2-2 | 控制器零参数校验 | 非法 cron / 重复 appName / 不存在的 jobGroupId / PUT 部分更新（cron 为 null）→ 全部 500 裸异常；`PUT /job` 无条件重置 triggerNextTime |
| P2-3 | 手动地址分组未实现 | `address_type=1` / `address_list` 从未被读取，route 只查 registry 表 |
| P2-4 | 执行器无优雅下线 | 只能等 90s 超时被 RegistryCleaner 清掉，无注销接口；重启有 90s 僵尸节点窗口 |
| P2-5 | 无鉴权 | 任何能访问 admin 的人可建任务/触发/停止；部署前需补登录/鉴权 |
| P2-6 | 多 admin 实例重复调度 | 无分布式锁，两个 admin 会把同一任务各触发一遍（Phase 2 已知，单机部署无碍） |
| P2-7 | JobHandlerRegistry 小问题 | 重复 handler 名静默覆盖（后者赢），空 value 可能 NPE；建议启动时校验 |
| P2-8 | executor `@EnableScheduling` 全局开启 | 宿主应用若自己也启用可能冲突；建议 `@ConditionalOnMissingBean` 控制 |
| P2-9 | 心跳 admin 地址未 trim | `adminAddresses.split(",")` 若配置带空格会拼出带前导空格的 URL |
| P2-10 | 时间轮秒级精度 | 延迟被 ceil 到整 tick（最坏 ~1s 误差），文档应注明"秒级精度" |
| P2-11 | 手动触发与 cron 触发不互斥 | 2026-08-26 思路一后，`block_strategy=SINGLE` 下两者共用同一 `runningJobIds` 互斥集，不再并行；`serial` 下仍可叠加 |

---

## 🔵 思路一：同步模型的三处执行语义修正（2026-08-26 会话，用户提出）

### 问题（同步请求-响应模型的固有缺陷）
- **假失败**：admin 等 10s 读超时放弃，只是客户端行为；执行器 Tomcat 线程照跑，副作用（发消息/写数据）全部发生，日志却记"失败"。
- **retry 重复执行**：任务执行 30s 时，每 10s 超时重试一次 → 同一任务并行跑 3 遍；非幂等 handler（扣款/短信）即事故。且 `route()` 每次重选机器，failover 下还会撒到多台执行器。
- **时间轮/线程池被长时间占用**：单次触发最长 ~30s（3 次尝试 × 10s），慢任务拖慢整个调度中心。

### 修复（`JobTriggerServiceImpl.java` + `JobLog.java`）
1. **超时不重试**：沿 `cause` 链识别 `SocketTimeoutException`，超时立即 break（不再重试）；仅"连接被拒绝（handler 未启动）"或明确失败码才重试。
2. **status=3 未知态**：`JobLog` 新增常量 `STATUS_UNKNOWN=3`，超时/被阻塞落该状态，handleMsg 提示"结果未知，勿重复触发"，不再误判为失败。
3. **`block_strategy=SINGLE` 互斥**：`runningJobIds`（`ConcurrentHashMap.newKeySet()`）判重，上一次未结束则丢弃本次触发并记 status=3 被阻塞日志，`try/finally` 释放；默认 `serial` 不互斥。

### 遗留边界（已由 Phase 2 异步回调根治，2026-08-27）
- 互斥是 admin 进程内的，多 admin 实例无分布式锁。→ **已根治**：互斥位从内存 `runningJobIds` Set → DB `status=0` 计数，admin 重启不丢、天然支持多 admin 实例。
- **执行 > 10s 的任务**：超时释放互斥位后，下一次触发仍会放行 → 重叠无法完全挡住。→ **已根治**：同步阻塞等待 → 「投递 ack + 执行器回调」，`timeout` 成为执行超时阈值（0=默认 60s）；互斥位到回调才释放，慢任务不再被 admin 读超时打断。
- 幂等要求未自动满足：仍需业务 handler 以 `logId` 去重。（Phase 2 未改动，仍适用）

---

## 备注

- 本次审查的代码基线：Phase 1 全部提交（HEAD = 830be30）。
- P0 修复后的验证：见会话记录（编译 + 双执行器轮询实测）。
- 思路一修复后的验证：见会话记录（slowHandler 15s 任务 + 0/5 cron → 超时 status=3、无重试日志、重叠被阻塞丢弃）。
