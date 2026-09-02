# ww-job 单机压测结果记录

> 目的：逐 Phase 记录实测数据、发现、结论。方法论与步骤见 `docs/load-test-plan.md`。
> 原则：每档跑完先记数据再下结论；所有"1000ms"等读数必须写清是执行耗时还是调度延迟。

---

## 0. 机器与环境基线

| 项 | 值 |
| --- | --- |
| 系统 | Windows 11 Home China |
| CPU | AMD Ryzen 5 4600H，6 核 12 线程 |
| 内存 | 15.4GB（压测启动前空闲 ~4.9GB） |
| JDK | 25 |
| 部署方式 | Docker Desktop 29.7.2（WSL2）起 MySQL 容器 `ww-job-loadtest-mysql`（mysql:8），admin 8080 / executor 8081 本机 `mvn spring-boot:run` |
| 压测库 | `ww_job_loadtest` @ localhost:3307（隔离 3306 开发库），admin 用 `loadtest` profile 启动 |
| 执行器 | samples 模块，app-name `sample-executor`，handler `loadTestHandler`（param 空/0=快任务，正整数=sleep，fail=抛异常） |

> ⚠️ 本机未按 plan §2.3 关干扰项（IDEA/浏览器未关），未调优；结论以"相对定位"为主，绝对容量仅作参考。

---

## 1. 结果总表（每档一行，持续追加）

| 档位(D) | N | 实测密度/s | 成功率% | status3% | P99(ms) | admin CPU% | DB写/s | 触发线程池拒绝 | 空秒数 | 同时运行峰值 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **P1 基线 D=10** | 100 | 10.1 | 100 | 0 | ~1000\* | 13%单核 | ~20 | 0 | 0 | ≈10 | 零压力，P99\* 为调度延迟（见 §3） |
| **P2a D=50**（sync_binlog=1） | 500 | 50→30 | 100 | 0 | - | 37%单核 | ~180 fsync/s | 0 | 0 | - | 拐点：DB fsync 提交瓶颈（F2-1） |
| **P2b D=50**（sync_binlog=0） | 500 | ~50 | 100 | 0 | - | 141%单核① | ~150 写/s | 0 | 3 | ≈50 | 单变量翻转密度恢复，根因坐实（F2-2） |
| **P3 D=100**（sync_binlog=0） | 1000 | 100→88 | 100 | 0 | - | 141%单核 | ~285 写/s | 0 | 0 | ≈100 | 二道墙：redo-log fsync（F2-6），soft ceiling ~88/s |
| **P4 D=300**（sync_binlog=0 + flush_log=2） | 3000 | 300→120 | 100 | 0 | - | 177%单核 | ~1200 DB往返/s | 0（无界队列，24线程仅~9忙） | 0 | - | 三道墙：HikariCP 默认池=10 × ~10 DB往返/触发（F2-8） |
| **P4b D=300 pool=30**（A/B，仅改池） | 3000 | 300→150 | 99.4 | **0.6** | - | 263%单核 | ~1500 DB往返/s | 0（24线程全 RUNNABLE，已饱和） | 0 | - | 单变量 A/B 坐实池墙：120→150/s；新墙=触发线程池24（F2-8修订 + F2-10） |
| **P5a D=50 慢**（param=2000，池30+双fsync关） | 500 | 50→51 | **23.5** | **13.0** | - | 4.3%单核 | ~50 写/s | 0（执行器拒绝≠触发池） | 1 | **147** | 执行器墙：成功吞吐=2219/184s=**12.1/s**（24线程÷2s），63.5%被拒(status=2)+13%阻塞(status=3)（F3-1/3-2） |
| **P5b D=100 慢**（param=2000） | 1000 | 100→107 | **11.8** | **6.7** | - | 9.7%单核 | ~107 写/s | 0（执行器拒绝≠触发池） | 4 | **157** | 墙平直：成功吞吐仍**12.7/s**（与 D 无关），拒绝率涨到 81.5%（F3-1） |
| **P6a D=300 混合 70/20/10**（池30+双fsync关+重启-Xmx512m） | 3000 | 162 | **60.5** | **0** | - | - | - | - | 0 | **148** | 队头阻塞：慢任务霸占执行器24线程、快任务排队，成功吞吐=**98.2/s**，cross-second 27.9%（F4-1/4-2） |
| **P6b D=300 95%快+5%fail** | 3000 | 153 | 95.3 | **0** | - | - | - | - | 0 | 24 | 失败链路正确：status=2=4.7%（全 fail handler），成功吞吐=**145.9/s**，cross-second 回 5.1%；但 **job_alert_state=0**=monitor 跳过空 alarmConfig（F4-3） |
| **P7a 并发手动触发 100×同job** | 1 | 手动 | - | **99(阻塞)** | - | - | - | - | - | 1 | **SINGLE 互斥精确成立：1 分发成功 + 99 status=3 被阻塞**，行锁串行化 avg 334ms/p95 567ms（F5-1） |
| **P7b 并发手动触发 100 job×1** | 100 | 手动 | 100 | 0 | - | - | - | - | - | 100 | 行锁 per-job：100 全分发成功，avg 131ms/p95 182ms，同/异 job 差 ~200ms/触发 = 锁等待（F5-1） |
| **P8a D=300 双admin**（池30×2+双fsync关+8080/8082） | 3000 | 142.5 | 99.88 | **0.12** | - | A/B各<1核④ | - | 0 | 0 | - | 集群对照：**双admin≈单admin（142.5 vs 149.6/s）不扩展**——墙=共享DB行锁串行化（claimNextTime/decide FOR UPDATE），稳态~85次/s锁等待（F6-1）；status=3 全为同秒双 claim 边界竞态、SINGLE 兜住无重复执行（F6-2）；cross-second 6.1%、p90 29s（尾部略劣） |
| **P8b D=300 双admin·F6-2修复**（同P8a配置，代码含秒级截断修复④） | 3000 | 127.0 | 100 | **0** | - | A/B各<1核④ | - | 0 | 0 | - | **F6-2 修复验证：status=3 33→0（同秒双 claim 竞态彻底消除），全 status=1、distinct=3000、cross-second 5.7%、interval p50/p90=21s/30s**；密度 127.0 vs P8a 142.5（-11%），per-job interval 未变 + 采样波动大（per-sec min=34/max=200），倾向饱和深度方差非修复代价（见 §Phase6 P8b） |
| **P9 D=300 双admin·30min稳定性**（同P8b配置，连跑 31min55s ④） | 3000 | 145.4 | 100 | **0** | - | A/B各<1核④ | - | 0 | 0 | - | **30 分钟持续饱和稳定性通过**：status=3 全程 0（F6-2 修复 30min 零复发，对比 P8a 修复前 3min 就有 33）、st3_60s/distinct60 各采样点全 0/3000、FGC 全程 0、堆 71~87% 震荡有界、线程 A85/B74 恒稳无线程泄漏、MySQL 连接 59~62 无耗尽；30min 均密度 145.4/s 高于 P8b 127.0/s → 坐实 P8b -11% 为采样方差非修复代价（见 §Phase7） |
| **P10 D=300 双admin·中途强杀adminA**（同P8b配置，饱和稳态中 taskkill A=8080，B 单机接管 235s） | 3000 | 131.5→142.5 | 100 | **0** | - | A死前后 B 各<1核④ | - | 0 | 0 | - | **故障恢复验证：executor 心跳/回调 failover 到 B 零丢失**——pre-kill 22216 行全 status=1、post-kill 33485 行全 status=1、无残留 status=0、巡检兜底无对象可扫、st3 全 0；**B 单机接管密度反超 142.5 vs 131.5/s（双 admin 行锁竞争税随 A 死消失，呼应 F6-1）**；distinct60 一次 2986 瞬态自愈（A claim-未投递点随进程消亡丢一拍，非故障）；巡检 SQL 的 NOW() 时区耦合见 §Phase8 F8-4 |
| **P11 executor-kill 小批量**（kill executor 13164，手动触发 91 次分四窗，双 admin 存活） | 91 | 手动 | - | 0 | - | - | - | - | - | - | **executor 故障恢复闭环**：①dispatch 到死地址 → 30×status=2（handle_code=500 `I/O error...Connection refused`，2.1s 全落账，**快速失败无残留 status=0**）；②RegistryCleaner 90s 在线移除（最后心跳+92s）；③registry 空后 route 返回 null → 30×status=2「无可用执行器」（handle_code=NULL，与①可区分）；④重启 executor → boot 2s @PostConstruct 广播重注册 → 30×status=1 恢复；**executor 离线 5.7min 全程零 status=0 孤儿**（见 §Phase9） |

① P2b 行 CPU 为 D=100 时采样，D=50 恢复态未单独采样（同配置，近似）。
② P6a/P6b 即 Phase 4（混合+失败场景），为 plan §Phase 4 的两半；CPU/DB 采样未做（P6 档无单独采样，参考 P4b 的 263% 单核）。
③ 重启验证：Phase 4 前按 §2.3 重启 admin/executor 至 `-Xmx512m -Xms128m`（jcmd 确认 MaxHeapSize=512MB），executor 重注册成功（registry sample-executor @ 127.0.0.1:8081）；Defender 排除脚本 UAC 被取消两次，**待用户手动管理员执行** `tools/add-defender-exclusions.ps1`。
④ P8a 为双 admin 集群对照：admin A 8080（PID 19084，累计 CPU 2794s）+ admin B 8082（PID 26468，启动 ~8min 累计 436s≈0.8 核），executor 8081 配 `admin-addresses: http://localhost:8080,http://localhost:8082` 双地址广播；MySQL 同时在线 60 个 HikariCP 连接（两池各 30）。CPU 均未饱和（<1 核/台），executor 快任务利用率 ~9%。

\* P99 秒精度取整：`1000ms` = 跨秒的调度延迟上界，快任务执行本身 <5ms。

---

## 2. 各 Phase 详情

### Phase 1 基线（D=10 快任务）— 2026-09-01 23:32~23:40

**配置**：N=100（K=10，cron `{i%10}/10`），param 空，warm-up 60s + 稳定观测 181s（23:32:40~23:35:41）。

**观测数据**：
- 密度采样 18 点：10.1/s 恒定（10~11/s 波动，无跳拍）
- status 分布：1838 条全 status=1 → 成功率 100%，status=3 = 0%
- 每秒触发数：181 个秒全部 ≥1（min=1，max=21，均值 10.1）→ 零空秒
- P99：~1000ms（见 §3 解读，非执行延迟）
- 资源：admin(8080) PID 17440 = 13% 单核 / 236MB；executor(8081) PID 27076 = 1.7% 单核 / 144MB

**结论**：D=10 系统无压力，正确性红线全绿。确立零负载参照。

### Phase 2 阶梯递增 — D=50（第一档）— 2026-09-02 00:12~00:16

**配置**：N=500（K=10），param 空，观测 00:12:06~00:15:08。测试中途被一次 `loadgen.py stop` 误停干扰（见 F2-0），恢复后观测。

**观测数据**：
- 密度：前 70s 精确 50/s → 后 90s 45.6/s → 持续衰减至 ~30/s 并稳定（00:15 后）
- status：8583 条全 status=1，status=3 = 0%（正确性无虞，但**触发数不足配置值**）
- 每秒分布：182 秒全 ≥1（无空秒），avg 47.2/s，min=1 / max=84
- distinct job_id = 500（无任务死亡），但每任务平均触发间隔被拉长到 **14.5~19s**（配置 10s）
- job 132（cron `0/10`）触发模式：**+10s/+20s 交替**——约一半轮任务晚到 ~10s，跳过边界

**根因（F2-1 写放大 + fsync 提交瓶颈）**：
- admin CPU 仅 **37% 单核**（非算力瓶颈，卡在 I/O）
- MySQL processlist 持续出现 `waiting for handler commit`
- mysql:8 默认 `innodb_flush_log_at_trx_commit=1` + `sync_binlog=1` → **每次 commit 双 fsync**（redo log + binlog）
- 每触发 ≥3 次事务 commit（job_log INSERT / job_info next_time UPDATE / 回调 job_log UPDATE）→ **每触发 ~6 次 fsync**
- Docker Desktop/WSL2 虚拟磁盘 fsync 慢 → commit 成为吞吐瓶颈 → triggerPool 积压 → 时间轮任务晚到 → claimNextTime 跳过 cron 边界
- 实测稳定吞吐 ~30/s ≈ fsync 容量上界（6 fsync/触发 × 30/s ≈ 180 fsync/s）

**结论**：该环境下系统持续触发吞吐被 **DB 提交 fsync 写放大** 封顶在 ~30/s，**D=50 已是拐点**。属环境（WSL2 fsync + mysql:8 默认持久化）与设计（每触发多次独立事务）叠加，非 CPU/线程池瓶颈。后续档位环境不变则预计平线 ~30/s。

**单变量 A/B 验证（F2-2，00:21:59 翻转 sync_binlog=0）**：
- 动作：仅 `SET GLOBAL sync_binlog=0`（动态参数，无需重启），其余全不变
- 效果：密度从 ~30/s **立即回到 ~50/s**，3 分钟稳定（18 采样均 45~58/s，均值 ≈50.2/s）；翻转瞬间 6s 写入 245 条（积压追补）
- 正确性：00:22:07~00:25:08 窗口 9066 条全 status=1，distinct_jobs=500，job 132 触发间隔回到 9~11s（+10/+20 跳边界模式消失）
- 附带观察：窗口 181s 有 3 个整秒空档（00:22:22/00:23:04/00:24:54，均在窗口中间非边界）—— +TICK_MS"宁可晚不可早"设计在接近容量时的秒级整批漂移（整批落下一秒），非丢任务红线（无 status=3）
- **测试基线变更**：从此 D=50 后续档位环境为 `sync_binlog=0`（binlog fsync 已去），遗留 redo-log fsync（`innodb_flush_log_at_trx_commit` 仍=1，需重启容器才能改，待阶梯验证是否成为下一道墙）

### Phase 2 阶梯递增 — D=100（第二档）— 2026-09-02 00:29~00:34

**配置**：N=1000（K=10），param 空，创建 00:29:39 后 warm-up ~70s，观测 00:31:02~00:34:03（181s）。清理了残留活跃任务 id 215。

**观测数据**：
- 密度：前 2 分钟 ~100/s 稳定 → 后 1 分钟降到 ~88/s 稳住（t+130s 起）；整窗均值 95.1/s（配置 100，缺口 ~5%）
- status：17220 条全 status=1，status=3 = 0%；distinct_jobs = 1000
- 每秒分布：min=7 / max=127 / avg=95.1；**空秒 = 0**（比 D=50 温和：无整秒缺口，只是均值下探）
- 资源：admin **141% 单核**（≈1.4 核），executor 22.8% 单核
- DB 现场：processlist 大量 `waiting for handler commit`（回调 UPDATE job_log）→ commit 相仍是墙

**根因（F2-6）**：sync_binlog=0 去掉 binlog fsync 后，`innodb_flush_log_at_trx_commit=1` 的 **redo-log fsync/commit** 成为第二道墙。每触发仍 3 次独立 commit（claim 更新 / job_log 插入 / 回调更新），~95/s × 3 ≈ 285 commit/s 逼近 WSL2 磁盘 fsync 容量。

**结论**：`sync_binlog=0 + flush_log=1` 配置下持续触发吞吐 **~88-95/s**，soft ceiling 已现。D≥300 预计平线 ~88/s。想继续找 CPU/线程池上限，需把 `innodb_flush_log_at_trx_commit` 也调低（重启容器）。

### Phase 2 阶梯递增 — D=300（第三档，双 fsync 全关）— 2026-09-02 00:44~00:48

**前置**：按 F2-7 流程恢复环境（容器重建后补建 job_group），MySQL 改为 `innodb_flush_log_at_trx_commit=2 + sync_binlog=0`（两 fsync 全关）。N=3000（K=10），param 空，观测窗口 00:44:39~00:47:40（181s）。窗口后停掉全部 3000 任务冻结数据。

**观测数据**：
- 密度：**稳态封顶 ~120/s**（配置 300/s，只交付 40%）；18 个采样点 102~135/s 锯齿波动，**不随窗口下降**（非积压衰减，是稳态墙）
- status：21747 条全 status=1，**status=3 = 0%**；distinct_jobs = **3000**（无任务丢失）
- 每秒分布：min=65 / max=174 / avg=119.5，**空秒 = 0**（无整秒缺口，但所有秒都只有一半负荷）
- 每任务触发间隔：配置 10s → **p50=22s / p90=31s / max=55s**（每个任务都"少跑"，中位只跑配置频率的 ~45%）
- 资源：admin **177% 单核**（≈1.8 核，非算力墙）；executor 28% 单核
- 线程池：`ww-job-trigger` **24 个**（availableProcessors=12 → max(4,12×2)=24，非此前记的 12），jstack 快照仅 **~9 个 RUNNABLE、15 个 TIMED_WAITING 空闲** → 触发线程池**未饱和**
- DB：无 fsync（两参数全关），MySQL 无 `waiting for handler commit`

**根因（F2-8 第三道墙 = HikariCP 默认连接池 10 × 写放大）**：
- 主 application.yml **无 HikariCP 配置 → max-pool-size 默认 10**
- 快任务每触发 **~10 次 DB 往返**：claimNextTime(2) + trigger selectById(1) + decide 事务(selectByIdForUpdate + countRunning + insert=3) + dispatch selectById(1) + route 在线地址(~1-2) + dispatchOne(updateById 地址 + updateById last_time=2)
- 10 连接 ÷ (10 往返 × ~8ms/次) ≈ **125/s** ≈ 实测 118/s（严丝合缝）
- 机制：触发线程在连接池等连接 → 触发完成被推迟 → `scheduledJobIds` 的 remove 推迟 → 任务重新入轮推迟 → **每个任务触发间隔被拉长**（10s→22s），但无任务丢失、无失败、无空秒

**结论**：调低 fsync 后第三道墙不是 CPU/线程池，而是**客户端连接池 + 每触发 ~10 次 DB 往返的写放大**。瓶颈链更新为：**binlog fsync(~30/s) → redo-log fsync(~88-95/s) → HikariCP 池 10 × ~10 DB往返/触发(~120/s) → 下一层未测（调大连接池后的 MySQL/CPU）**。CPU 至今从未成为墙（D=300 仍仅 177%）。正确性红线全程全绿，代价全部由"触发频率被拉长"承担。

### Phase 2 A/B — D=300 pool=30（单变量验证 F2-8）— 2026-09-02 01:16~01:21

**前置**：环境同 P4（双 fsync 全关），**唯一改动 = `application-loadtest.yml` 的 `hikari.maximum-pool-size` 10→30**。drain 到 0 后重新启用批1（1-3000），验证恰好 N=3000、0 泄漏。观测窗口 01:16:16~01:19:17（181s），结束后 drain 冻结数据。

**观测数据**：
- 密度：**稳态 ~150/s**（pool=10 基线 120/s，**+25%**）；18 采样点 116~181/s，全窗 27081 行 = **149.6/s**
- status：{1: 26917, 3: 164}，**status=3 = 0.6%（首次破红线，见 F2-10）**；distinct_jobs = **3000**
- 每秒分布：min=72 / max=226 / avg=149.6，**空秒 = 0**
- 每任务触发间隔：配置 10s → **p50=20s / p90=22s / max=46s**（略好于 pool=10 的 p50=22s / p90=31s）
- 资源：admin **263% 单核**（pool=10 时 177%）；jstack **24/24 触发线程 RUNNABLE**（pool=10 时仅 ~9 忙）
- DB：无 fsync；~1500 DB往返/s

**结论（F2-8 修订）**：池 10→30 密度跟涨（120→150/s）→ **连接池墙坐实**；但池不再饱和，**新墙 = 触发线程池 24 线程**——24/24 RUNNABLE + 每触发 ~150ms 总耗时 → 24÷0.15 ≈ 160/s 与实测吻合。触发线程池由 `ScheduleHelper` 硬编码 `max(4, availableProcessors×2)=24`，**不可配置**，要突破需产品改造（线程池可配，可能还需再加连接池）。写放大（每触发 ~10 DB 往返）仍是统一根因。

### Phase 3 最大同时运行任务数（全慢任务）— D=50 / D=100 — 2026-09-02 01:31~01:39

**目的**：换维度测执行器并发上限——全部 `param=2000`（每任务睡 2s），阶梯 D=50→100，以 status=0（执行中）job_log 峰值 = 同时运行任务数（正是 plan §Phase 3 的方法论，也兑现 F1-3 的预判：慢任务下"同时运行数"比 P99 秒精度更可靠）。环境同 P4b（池 30 + 双 fsync 全关），admin/executor 均本机单实例。

**执行**：D=50 → N=500（ids 6001-6500），观测 01:31:16~01:34:20（184s）；D=100 → N=1000（ids 6501-7500），观测 01:35:56~01:39:01（185s）。观测工具 `observe_concurrent.py` 每 5s 采样 joblog total + status=0 数；窗口后 `analyze_window.py` 出状态分布；每档跑完 drain 到 0（D=50 复活 1、D=100 复活 4，F2-9 再次现形）。

**D=50 观测**：
- 密度 51.4/s（配置 50/s，row=9454）；status **{2: 6003(63.5%), 1: 2219(23.5%), 3: 1232(13.0%)}**
- **成功吞吐 = 2219/184s = 12.1/s**；running 持续 123~147（max 147），几乎全程贴 124 并发墙
- admin CPU **4.3% 单核**（几乎空闲，瓶颈全在执行器）

**D=100 观测**：
- 密度 107.4/s（配置 100/s，row=19876）；status **{2: 16197(81.5%), 1: 2348(11.8%), 3: 1327(6.7%), 0: 4}**
- **成功吞吐 = 2348/185s = 12.7/s**；running 128~157（max 157）
- admin CPU **9.7% 单核**

**结论（F3-1）**：执行器并发墙坐实且**平直**。`ExecutorAutoConfiguration` 硬编码 `ThreadPoolExecutor(cores=12, cores×2=24, ArrayBlockingQueue(100), AbortPolicy)` → **持续成功吞吐 = 24 线程 ÷ 2s ≈ 12/s，与 D 无关**（D=50 12.1 / D=100 12.7）；超出部分一律被拒绝（status=2），拒绝率随 D 线性上升（63.5%→81.5%）而成功吞吐钉死不动。同时运行峰值 ~124（24 线程 + 100 队列），观测 max 147 含 HTTP 在途/前档残余。慢任务维度 admin 全程空闲（4~10% 单核），与快任务维度（admin 263%）完全互补。

### Phase 4 混合任务 + 失败场景 — 2026-09-02 09:41~09:54

**前置（§2.3 部分兑现，重启 -Xmx512m）**：按用户要求执行 Defender 排除（`tools/add-defender-exclusions.ps1`，UAC 被取消两次，待用户手动管理员运行）；重启 admin/executor 至 `-Xmx512m -Xms128m`（jcmd 验证 MaxHeapSize=512MB），executor 重注册成功（registry 显示 sample-executor @ 127.0.0.1:8081）。环境其余同 P4b（池 30 + 双 fsync 全关）。

**Test A — D=300 混合 70/20/10**（3000 任务：70% 快 + 20% 2s 慢 + 10% 5s 慢；观测 09:41:48~09:44:57，189s）：
- 窗口 **30645 行**，density 162/s；status **{1: 18561(60.5%), 2: 12084(39.4%), 3: 0}**；distinct 3000；空秒 0；max_running 148
- **成功吞吐 98.2/s**；**cross-second 27.9%**（快任务真实排队等慢任务，对照纯快基线 5.1%）
- 与 P6b 对照：混入 30% 慢任务 → 成功吞吐 145.9→98.2/s（**-33%**），cross-second 5.1%→27.9%

**Test B — D=300 95%快 + 5% fail**（3000 任务：2850 快 + 150 fail handler；观测 09:48:51~09:52:02，191s）：
- 窗口 **29252 行**，density 153/s；status **{1: 27867(95.3%), 2: 1385(4.7%), 3: 0}**
- 150 个 fail 任务全按预期 status=2（handleMsg=`load test fail`）；fail 任务 9.23 次/任务 ≈ 快任务 9.78 次/任务（cron 频率正常，无异常加速）
- **成功吞吐 145.9/s**；cross-second 5.1%；max_running 24（全快任务，执行器不积压）
- **job_alert_state = 0 行**：窗口内 1385 次失败，但 JobFailMonitor（30s 扫描）对 alarmConfig 为空的作业全部跳过（JobFailMonitor.java:71-72）→ 无告警无记录

**结论（F4-1/4-2/4-3）**：混合负载下共享执行器池成为融合墙，成功吞吐 = 24 线程 ÷ 加权平均时长；快任务被慢任务连带降速（cross-second 27.9% 实证队头阻塞，见 F4-1/4-2）。失败场景端到端正确（status=2 + fail handler），但告警链路对"未配置 alarmConfig 的任务"完全静默（F4-3）。

### Phase 5 并发手动触发（SINGLE 互斥）— 2026-09-02 10:13~10:15

**目的（plan §Phase 5）**：对单个任务同毫秒并发 `POST /job/{id}/trigger` 100 次，验证 SINGLE 互斥（预期 1 条分发 + 99 条 status=3 被阻塞）并观察 DB 行锁竞争。手动触发走与 cron 相同的 `JobDecisionService.decide()`（`selectByIdForUpdate` 行锁 + `countRunning>0 → 记 status=3 阻塞日志`），且 decide 不检查 triggerStatus，停用任务也能手动触发 → 建号即停用、避免 cron 干扰。

**Test 5a — 同 job 100 并发（job 13501，param=2000 睡 2s，SINGLE，停用）**：
- `trigger_concurrent.py --job-id 13501 --count 100 --concurrency 100` → 100 发 HTTP 全 200（阻塞不体现在响应码）
- **job_log：manual 触发 = {1: 1, 3: 99}** —— 精确 1 分发 + 99 条"任务上一次执行尚未结束，本次触发被阻塞丢弃"
- **行锁串行化**：wall 0.82s、per-req **avg 334ms / p95 567ms**（100 个 decide 全部在同一行锁上排队，每次等前一个提交）

**Test 5b — 100 个不同 job 各 1 次并发（job 13502-13601，快任务，停用）**：
- `trigger_concurrent.py --job-ids 13502..13601 --concurrency 100`
- **job_log：manual 触发 = {1: 100, 3: 0}**，100 个不同 job 全部并行分发成功
- **行锁粒度对照**：wall 0.55s、per-req **avg 131ms / p95 182ms** —— 同 job 比异 job avg 多 ~200ms/触发（锁等待），证明行锁是 **per-job** 而非全局

**附带观察**：job 13501 建号后停用前（cron `0/1` 每秒触发、runtime 2s）跑了 ~6 秒，cron 触发 {1: 2, 3: 2} —— 执行时间超过 cron 间隔时 SINGLE 直接把一半触发阻塞掉，与 F3-2（慢任务 + 高密度下 status=3 抬头）同机制的生产路径复现。

**结论（F5-1）**：SINGLE 互斥正确性红线成立且可量化——**并发手动触发任意数量，实际执行恒为 1 条，其余全部被防重丢弃**（无重复执行）。实现代价是同一任务的高频触发在行锁上串行排队（~200ms/触发），这是"用锁换正确性"的合理取舍；不同任务互不干扰（per-job 锁），多任务并发不受影响。

---

## 3. 关键发现与收获（逐 Phase 追加）

### Phase 1 收获

**F1-1：P99 的真实语义 = 调度延迟，不是执行耗时。**
`job_log.trigger_time/handle_time` 是**秒精度 DATETIME**：同秒完成 → diff=0ms；跨秒 → 显示 1000ms。那 ~6% 的"1000ms"行，经查证来自 `ScheduleHelper.java:99` 的 `addTask(delay + TICK_MS)`——这是修跳拍 bug 的**故意设计**：+1s 保证触发"永不提前"，代价是"至多晚 1 个 tick"。所以快任务执行本身 <5ms，P99 应解读为**调度延迟上界（0~1s）**。后续 Phase 的延迟解读必须区分这两者。

**F1-2：MySQL 容器 UTC vs 应用上海时间（差 8h）。**
docker 容器 `NOW()` 是 UTC，应用经 JDBC `serverTimezone=Asia/Shanghai` 写入上海时间。用 `NOW() - INTERVAL` 过滤 job_log 会把全量数据算进来。**必须用显式时间边界**（host 时钟即上海时间，可作窗口边界）。

**F1-3：秒精度导致的 P99 量化陷阱。**
同秒内完成显示 0ms、跨秒显示 1000ms，中间值（如 300ms）被抹掉。Phase 3 慢任务（param=2000）会大量跨秒，届时"同时运行数"更可靠，P99 精确值需靠采样执行器侧耗时补足。

**F1-4：触发密度公式验证。**
`N = D×K`、cron `{i%K}/{K}` 实测密度 10.1/s 与 D=10 吻合；同 residue 的 D 个任务在同一个秒内齐发（每个秒一个 10 任务突发），是设计如此，非异常。

### Phase 2 收获

**F2-0：压测 driver 的 stop 分页缺陷可误停新任务。**
`loadgen.py stop` 原逻辑以 `len(records) < size` 断页，某次分页异常导致无限循环空转半小时，把随后新建的任务也误停（Phase 2 一度密度掉 0）。已改为取首个响应的 `pages` 上界兜底。教训：**测试工具自身的边界条件也要审**。

**F2-1：D=50 拐点根因 = DB 提交 fsync 写放大（write amplification）。**
mysql:8 默认 `innodb_flush_log_at_trx_commit=1` + `sync_binlog=1` → 每次 commit 双 fsync（redo log + binlog）；每触发 ≥3 次独立事务 commit（job_log 插入 / next_time 更新 / 回调更新）→ 每触发 ~6 fsync。WSL2 虚拟磁盘 fsync 慢 → commit 成吞吐瓶颈，密度 50→30/s，admin CPU 仅 37% 单核（I/O 卡住非算力）。正是 plan §8 预判的写放大瓶颈被环境放大。

**F2-2：单变量 A/B 坐实 fsync 假设（配置敏感项）。**
只翻转 `sync_binlog=0`（动态、不重启），密度 30→50/s 恢复且 3 分钟稳定，正确性全绿。证明该档的墙是 binlog fsync，不是 CPU/线程池/网络。→ 压测方法论：遇到环境性墙（如 fsync）用**单变量翻转**确认，再决定是否调低持久化继续找下一道墙，而不是拿一个"默认配置 30/s"的笼统数字当结论。

**F2-3：接近容量时的秒级整批漂移。**
D=50 恢复后窗口 181s 有 3 个整秒空档（00:22:22/00:23:04/00:24:54，均窗口中间），是 +TICK_MS"宁可晚不可早"设计在触发池接近饱和时的自然表现（整批落下一秒），无 status=3、无丢任务，属调度延迟语义而非正确性红线。

**F2-4：loadgen.py stop 传参名与后端不符（`current` vs `page`）。**
后端 `/job/page` 绑定的是 `page`（非 MP 惯例 `current`），`loadgen.py stop` 传 `current` 被后端无视 → 每次翻页都返回第 1 页同一批记录 → 只停得掉一页任务。这把 F2-0 的"无限循环"真正解释清了：旧代码靠 `len(records) < size` 断页，但 API 永远返回满页 → 永不退出，且第 1 页（id 倒序）永远含新建的最高 id 任务 → 误停新任务。之前加的 `pages` 上界只是把无限循环**压成提前退出**，病根在传参名，本次才连根修掉（`current`→`page`）。前端一直用 `page`，无此问题。附带观察：某次 stop 有 1 个任务（id 215）两次循环都漏停，需手动补停——stop 未校验每个 POST 的返回值，静默失败不易察觉。

**F2-5：sync_binlog=0 后 admin 成为真正的执行者。**
D=100 时 admin CPU 141% 单核（≈1.4 核，D=10 时 13%，近似线性随密度增长），executor 22.8% 单核。fsync 墙移除后系统才真正开始吃 CPU——佐证 F2-1 的"此前卡在 I/O 而非算力"。

**F2-6：第二道墙 = redo-log 提交 fsync（innodb_flush_log_at_trx_commit=1）。**
D=100 前 2 分钟 ~100/s，t+130s 后降到 ~88/s 稳住（soft ceiling），整窗 95.1/s。processlist 再现 `waiting for handler commit`（回调 UPDATE job_log），commit 相仍是墙，只是从 binlog 换成 redo-log。每触发仍 3 次独立 commit → ~285 commit/s 逼近 fsync 容量。D=100 比 D=50 温和：空秒 0（D=50 有 3），仅均值下探 + 弱秒（min=7）。**瓶颈链至此完整：binlog fsync → redo-log fsync → （下一层待测：CPU/触发线程池）**。三层每层都源自同一个设计点——**每触发 3 次独立事务 commit 的写放大**（F2-1），这是访谈上最能展开的设计话题。

**F2-7：容器重建后 job_group 分组丢失 → executor 无法重注册（环境重建流程必修）。**
调低 fsync 重建 MySQL 容器（新 volume）后，admin 重启用 schema.sql 重建了全部空表——但 schema.sql **只种子 `sys_user`（admin/admin123）和 `job_lock`，不种 `job_group`**。executor 心跳照常每 30s 广播 `/registry`，但 `RegistryService.upsert` 先查 `job_group` 按 `app_name='sample-executor'`，查不到直接 `ReturnT.fail("执行器分组未注册")` → `/registry/list` 永远空，压测 driver 的 `--group 1` 也没对象。修复：走 admin 自己的 `POST /jobgroup`（前端"执行器列表"同一条路）建回 `{appName:'sample-executor', title:'sample executor', addressType:0}`，executor 下个 30s 心跳周期自动重注册。**教训：环境重建步骤 = 起容器 → 起 admin → 建分组 → 验 `/registry/list` 非空 → 才建任务**；此坑非产品 bug（分组本就该用户管理），属压测环境重置纪律。

**F2-8（修订）：第三道墙 = HikariCP 默认连接池 10 × ~10 DB 往返/触发；A/B 验证后新墙 = 触发线程池 24。**
双 fsync 全关后 D=300 封顶 ~120/s（请求 40%），jstack 显示 24 触发线程仅 ~9 忙 → 指认 HikariCP 默认池 10 为墙（10 ÷ (10×~8ms) ≈ 125/s）。**单变量 A/B（池 10→30，仅改 `application-loadtest.yml` 的 hikari.maximum-pool-size）**：密度 120→**150/s**（+25%，全窗 149.6/s），admin CPU 177%→263% 单核，**jstack 24/24 触发线程全 RUNNABLE**——池墙坐实（密度跟涨），但池不再是墙，**新墙 = 触发线程池 24 线程**（`ScheduleHelper` 硬编码 `max(4, availableProcessors×2)=24`，**不可配置**；每触发 ~150ms 总耗时 × 24 ≈ 160/s 与实测吻合）。瓶颈链更新：**binlog fsync(~30/s) → redo-log fsync(~88-95/s) → HikariCP 池 10×10 往返(~120/s) → 触发线程池 24(~150/s) → 下一层待测（调大线程池后的 MySQL/CPU）**。统一根因仍是**写放大**：每触发 3 次事务 / ~10 次 DB 往返，在 fsync 墙、连接池墙、线程池墙下换着方式当瓶颈，是 ww-job 最核心的性能杠杆。要突破 150/s 需产品改造：触发线程池可配化（+ 可能再加连接池），或压缩每触发的 DB 往返次数。

**F2-9：高负载下 stop 不可靠——in-flight 触发用旧实体 `updateById` 把已停任务"复活"（产品缺陷）。**
`/job/{id}/stop` 无条件置 triggerStatus=0 且**永远返回 ReturnT.success()**（响应码无法检出真实失败）；而 `JobTriggerServiceImpl` 的 `dispatchOne`/`dispatch` 里 `jobInfoMapper.updateById(job)` 用 `trigger()` 开头 `selectById` 加载的**旧对象整实体写回**（MyBatis-Plus 默认写全部非空字段，含 triggerStatus）。stop 若落在 in-flight 触发的写回窗口内（可宽达 ~10s，因 HTTP 分发 connect 3s/read 10s），旧 triggerStatus=1 被写回 → **任务复活继续跑**。实测：drain 循环（停全量→等 5s→重扫）iter1 停 4220，97s 后 iter2 又见 **51 个复活**、iter3 又 1；pool=30 档后 iter1 停 3000，iter2 又 **25 个**、iter3 又 1（~0.9%）。**影响**：压测档间清理必须"验到 0"（循环全量停 + 等 5s + 重扫，见 loadgen.py cmd_stop），按响应码校验无效；产品上 stop 在并发触发下不保证持久，属真实缺陷。修复方向：dispatchOne/dispatch 避免整实体覆盖（`updateById` 只更新指定列，或用乐观锁 / `LambdaUpdateWrapper` 按需更新）。

**F2-10：D=300 pool=30 首次出现 status=3（SINGLE 阻塞防重），0.6%。**
池放大到 30、密度 150/s 后，窗口内 164/27081（0.61%）触发被 SINGLE 阻塞丢弃（handleMsg=`任务上一次执行尚未结束，本次触发被阻塞丢弃`，JobDecisionService.java:40），根因是个别任务回调延迟 >10s，下一次触发（cron 每 10s）到达时上一次执行仍未回调 → `countRunning>0` → 防重丢弃。pool=10（120/s）时 status=3=0%。这是 block_strategy=SINGLE 的防重叠正确性在更高密度下**显形**（红线事件，是主动防重、非丢任务）。代价：触发频率被拉长（p50 20s）+ 少量触发被防重丢弃。

### Phase 3 收获

**F3-1：执行器线程池 = 慢任务维度的墙（持续吞吐 ~12/s、并发 ~124，且与 D 无关）。**
`ExecutorAutoConfiguration` 硬编码 `ThreadPoolExecutor(cores=12, cores×2=24, ArrayBlockingQueue(100), AbortPolicy)`，满则 `AbortPolicy` 拒绝、/run 返回"执行器繁忙"→ status=2。全慢任务（param=2000，每任务睡 2s）两档实测：**成功吞吐都钉死在 ~12/s**（D=50 = 12.1/s、D=100 = 12.7/s，精确等于 24 线程 ÷ 2s），同时运行峰值 ~124（24 线程 + 100 队列），超出部分全部被拒——拒绝率随 D 上升（63.5%→81.5%），成功吞吐纹丝不动。admin 全程 4~10% 单核（空闲）。**启示：调度平台容量 = 工作负载画像的函数，两类墙独立**——快任务受 admin 写放大限制（~150/s，调连接池/触发池），慢任务受执行器 `线程数÷任务时长` 限制（24/T，调执行器线程池或加执行器实例）。这是访谈/方案上最能展开的"容量维度拆分"话题。

**F3-2：SINGLE 防重在慢任务 + 高密度下变成"常态静默丢单"（13%/6.7%）。**
status=3 占比 D=50 = 13.0%、D=100 = 6.7%，远超快任务 pool=30 档的 0.6%。机制：执行器队列积压让任务从 dispatch 到回调超过 10s（cron 间隔）→ 下一次触发到达时 `countRunning>0` → 被防重丢弃（JobDecisionService.java:40）。**执行器越堵、回调越晚 → 防重丢单越多 → 实际执行频率越低**，形成自我放大。status=3 在日志/前端是静默的（不告警、不算失败），用户感知不到"任务其实没跑"，值得产品层面对策：慢任务建任务时提示增大 cron 间隔，或在状态页把"被防重丢弃"与"真实失败"分开展示。

### Phase 4 收获

**F4-1：共享执行器池的队头阻塞（head-of-line blocking）= 混合负载的真实成本。**
Test A（70/20/10 混合）成功吞吐 **98.2/s**，Test B（95% 快）**145.9/s** —— 混入 30% 慢任务让整体降速 **33%**，远超"慢任务各自被拒"的局部损失：慢任务（2s/5s）霸占 24 个执行器线程后，快任务只能在 ArrayBlockingQueue 里排队等待，快任务成功也被连带拖慢。**容量规划必须按负载画像算**，混合场景的成功吞吐 = 24 线程 ÷ 加权平均时长，不能把快/慢各自上限简单相加。这也是 XXL-Job 等平台建议"慢任务专用执行器/分组隔离"的根因 —— 本平台目前所有 handler 共用一个执行器池，无分组隔离，混合部署是最差画像。

**F4-2：cross-second 率 = 队头阻塞的可量化指标（27.9% vs 5.1%）。**
纯快基线 cross-second 仅 5.1%（其中大部分是 +TICK_MS 调度延迟）；混合下暴涨到 **27.9%** —— 差出的 ~23 个百分点就是快任务真实排队等慢任务的时间（秒精度下 handle_time ≠ trigger_time）。它补上了 P99 秒精度量化陷阱（F1-3）看不到的"执行层排队"，是混合负载最便宜有效的健康指标。

**F4-3：失败场景端到端正确，但告警链路对空 alarmConfig 任务完全静默（产品缺口）。**
5% fail 任务全部正确落 status=2 + handleMsg='load test fail'（150 任务 × 全部失败），失败链路本身无缺陷。但窗口内 1385 次失败后 **job_alert_state 恒为 0 行** —— `JobFailMonitor`（30s 扫描、10min 去重）对 `alarmConfig` 为空的作业直接跳过（JobFailMonitor.java:71-72），既不写 alert_state 也不发通知。对真实用户意味着：**建任务时不配告警人 → 任务失败无任何通知、监控页无记录**，job_log 里 status=2 是唯一痕迹。产品建议：跳过"无告警人"合理，但应至少打一条 skip 审计日志，或在日志详情展示"已告警/未告警（未配置告警人）"，避免运维误以为监控已生效。

**F4-4（工具教训）：负载进行中跑 OFFSET 分页拉 joblog 会因并发插入分页漂移而重复计数。**
Test A 在负载未停时跑 `analyze_window.py` 得到 36548 行，drain 冻结后重拉只有 **30645**（虚高 19%）；Test B 同理 35513 → 29252（虚高 21%）。根因：joblog 持续插入时 OFFSET 分页的页边界漂移，同一批行被跨页重复读到，且**虚高比例恰好与 status 分布成比例**（各状态占比不变，总量虚高）——容易骗过肉眼。规避：**窗口统计必须 drain 冻结数据后跑**（本文件所有最终数字均为冻结态重拉，并经 DB 双口径核对一致），或工具改游标分页（`id < last_id` 滚动）。

### Phase 5 收获

**F5-1：SINGLE 互斥正确性红线精确成立，代价是行锁串行化（per-job 粒度）。**
同 job 100 并发手动触发 → 恰好 **1 分发 + 99 status=3 被阻塞**（handleMsg="任务上一次执行尚未结束，本次触发被阻塞丢弃"），无任何重复执行——SINGLE 的"防重叠"语义在极端并发下依然精确。机制与成本：`decide()` 用 `selectByIdForUpdate(jobId)` 行锁串行化同一 job 的全部触发（同 job 并发 avg 334ms vs 异 job 131ms，差 ~200ms/触发 = 锁等待），再用 `countRunning>0` 决定阻塞——**同一任务高频触发在锁上排队（延迟成本），多任务并发互不干扰（per-job 锁，非全局）**。这是"用锁换正确性"的合理取舍；产品视角：SINGLE 语义本就拒绝并发执行，故锁等待不算缺陷，但接口调用方需知悉"同一 job 的超高并发触发会线性吃锁延迟"。此机制正是 F3-2 里 cron 侧 status=3 的来源（执行时间 > cron 间隔时被防重丢弃），Phase 5 用手动触发把同一机制确定性复现了一次。

### Phase 6 收获（双 admin 集群对照）

**P8a 实测**：双 admin（8080+8082，同 loadtest profile 同库，各 HikariCP 池 30、各触发线程池 24）重跑 P4b 批次（1-3000，D=300 纯快任务，K=10），观测 10:46:50~10:49:56（186s）。drain 3 轮收敛（复现 F2-9 复活 39 个）后冻结态 + DB 双口径核验一致：**26502 行 / 142.5/s**，status {1:26469 (99.88%), 3:33 (0.12%)}，distinct=3000（全任务触发、无遗漏、无重复执行），cross-second 6.1%，per-job interval p50=20s/p90=29s/max=49s。两台 admin 均高强度参与（A=8080 累计 CPU 2794s、B=8082 ≈0.8 核，均 <1 核未饱和；executor 8081 正常回调）；MySQL 同时在线 60 个 HikariCP 连接。

**F6-1：双 admin 不扩展——共享 MySQL 行锁串行化是墙（集群对照核心结论）。**
D=300 双 admin **142.5/s** vs 单 admin P4b **149.6/s**（同一批、同配置、仅 admin 数 1→2）——**吞吐不仅没涨还略降**。触发线程翻倍（24→48）、连接池翻倍（30→60）、admin 未 CPU 饱和，吞吐纹丝不动 → 墙不在 admin 侧，在**共享 DB 的 `claimNextTime`/`decide` 行锁串行化**（`selectByIdForUpdate(jobId)`，JobInfoMapper.java:15）。机制：每触发点两台 admin 都抢同一 job 行锁，胜者推进 next_time，败者阻塞后读已推进值放弃 → **每个点从 1 次锁获取变成 2 次（+1 次败者等待）**。实测 `Innodb_row_lock_waits` 240s 内 +17815（稳态 ~85/s，单 admin 趋近 0），`Innodb_row_lock_time` +89.8s。**瓶颈链新增段：单 admin 墙=触发线程池 24（~150/s）→ 双 admin 墙=共享 DB 行锁串行化（~142/s）**。结论：纵向往 admin 加实例无法突破共享 DB 的调度写放大；要扩展需产品改造——按 job 分片让每 admin 只 claim 自己分片（消除败者锁等待），或把 claim 的行锁换成 CAS（`UPDATE ... WHERE next_time=旧值`，0 行受影响即放弃）。

**F6-2：claimNextTime 秒边界竞态 → 相邻 cron 点被压成同秒双触发（多 admin 放大；非 SINGLE 任务 = 毫秒级连发两次）。**
`CronUtil.nextTime` 用 Spring `CronExpression.next()` 返回**秒级边界**（ms=0，如 10:47:04.000），而 `claimNextTime` 的幂等检查是 `lastNext > now`（now 毫秒级，JobTriggerServiceImpl.java:69）。饱和态下胜者 A 对**落后点 X**（如 10:46:44）的 claim 会晚到 10:47:03.999 才执行、把 next 推进到**相邻点 Y=10:47:04.000**；败者 B（同一时间轮任务、阻塞在 A 的行锁上）在 10:47:04.001 拿锁后读到 `lastNext=10:47:04.000`，`lastNext > now`=false → 误判 Y 可 claim → 放行 → `decide()` 见 A 的 X 执行仍 status=0 → 落 status=3「被阻塞」→ **被 SINGLE 兜住、无重复执行**。Y 本应在正常节奏下被整段跳过（饱和下每任务 p50=20s，约每两个点才跑一次），竞态把它压到 X 之后 2ms 连发。实测 P8 全窗 **33/26502（0.12%）status=3，100% 同秒双触发**（每条同秒都配一条 status=1，handle_time 全 NULL 未分发）；对照组单 admin P4b 的 164 条 status=3 **0% 同秒**——单 admin 也偶发 X-Y 压缩（+TICK_MS 使 Y 晚 ~1s、跨秒故不同秒），但双 admin 的行锁交接把间隔压到亚秒级（同秒）。**产品缺陷边界**：SINGLE 策略下只是白耗一次 claim+锁 + 一条噪音 status=3；若任务 blockStrategy 非 SINGLE，两个相邻 cron 点会**毫秒级连发两次执行**（真实重复执行风险，不因单/双 admin 而消除，饱和下都会偶发）。**修复方向**：幂等判断改秒级截断——`nowSec=(now/1000)*1000`，`lastNext >= nowSec` 即视为"边界仍在当前秒、尚未到期"返回 false，杜绝败者抢相邻点（末秒边界 > 当前秒首时才放行，跨秒仅多等 ≤1s，饱和下无感）；更彻底的方案是 claim 携带触发点做 CAS（`UPDATE ... WHERE next_time=旧值`），从根上消除"读-判-写"竞态。

**P8b 实测（F6-2 修复后复测，2026-09-02 12:37~12:41）。**
修复提交 `44e0b16`（claimNextTime 秒级截断：`claimable(lastNext, now)` 抽出为包内静态方法，JobTriggerServiceImpl.java:69,86-90）。双 admin 重建重启（A=8080 PID 18712 / B=8082 PID 28060，同 P8a 配置：loadtest profile 同库、各池 30、各触发线程池 24）后重跑 P4b 批次（1-3000，D=300 纯快任务，K=10）。观测 12:37:33~12:40:40（187s），drain 3 轮收敛（复现 F2-9 复活 36 个）后冻结态 + DB 双口径核验一致：**23749 行 / 127.0/s，status 全 {1:23749}（100%），status=3 = 0（修复前 33），distinct=3000，cross-second 5.7%，per-job interval p50=21s/p90=30s/max=59s**。
**决定性验证通过**：P8a 全窗 33 条 status=3 中 33/33 为同秒双触发（每条同秒配 status=1、handle_time 全 NULL 未分发）；P8b 决定性 SQL（status=3 JOIN 同 job 同秒 status=1，窗口内）返回 **0** → 同秒双 claim 竞态彻底消除、SINGLE 兜底噪音清零、无重复执行；跨秒真实阻塞（F2-10 机制）正常路径不受影响（cross-second 5.7% 仍在正常范围，非 SINGLE 相邻点连发从"毫秒级同秒"退化为正常跨秒间隔）。
**密度观察（诚实标注）**：127.0/s vs P8a 142.5/s（-11%）。机制上修复仅在压缩点增加追赶 claim（P8a 仅 33 个压缩点 × 1 次锁 ≈ +0.4% 锁获取，不足以解释 10%）；且 per-job interval p50/p90 几乎未变（20/29→21/30，单任务服务率不变）；P8b 采样波动大（observe 10s 采样 86~170/s、per-sec min=34/max=200）→ 差异倾向**饱和深度/运行方差**而非修复回归。如需严格坐实修复无吞吐代价，可回退旧代码做 A/B 对照（本档未做）。

### Phase 7 收获（30 分钟稳定性）

**P9 实测（F6-2 修复后长跑稳定性验证，2026-09-02 12:47~13:25）。**
P8b 同批次（1-3000，D=300 纯快任务，K=10，全 SINGLE）连跑 31min55s，观测窗 12:50:35~13:22:30（1915s，11 采样点×180s 监控）。双 admin 配置不变（A=8080 PID 18712 / B=8082 PID 28060，loadtest profile 同库、各 Hikari 池 30、各触发线程池 24）。负载 enable 1-3000 打满，drain 3 轮收敛（复现 F2-9 复活 40 个、iter3 清 0）后 DB 冻结核验：**278360 行 / 145.4/s，status 全 {1:278360}（100%），status=3 = 0（30 分钟全程 0），distinct=3000，per-sec min=47/avg=145.4/max=257（1915/1915 秒无空秒），cross-second 19090/278360=6.86%，per-job interval p50=20s/p90=29s/max=60s**。

**F7-1：F6-2 修复 30 分钟持续饱和零复发，且无吞吐代价（顺带坐实 P8b 的 -11% 是采样方差）。**
长跑全程 status=3=0（修复前 P8a 仅 3 分钟窗口就有 33 条）——同秒双 claim 竞态在 30 分钟高负载下稳定不复发，SINGLE 兜底噪音清零。更有价值的旁证：同一修复（44e0b16）下 P9 的 30min 均密度 **145.4/s 反而高于 P8b 的 127.0/s、与 P8a 142.5/s 同量级**；per-job interval 三档一致（P8a 20/29/49、P8b 21/30/59、P9 20/29/60，服务率相同）→ P8b 的 -11% 确为 187s 短窗的饱和深度方差，非修复回归；修复机制成本 ≈0（P8b 档留的"回退 A/B 对照"由此档自然补上，无需再做）。

**F7-2：无内存漂移、无线程泄漏、无连接池耗尽、无 GC 异常（稳定性核心结论）。**
11 采样点（30 分钟）各资源曲线：**线程数 A=85 / B=74 全程恒定**（从 warmup 到结束零增长，无线程泄漏）；**MySQL processlist 59~62 恒定**（两 Hikari 池 30×2=60 + admin 自身连接，未逼近耗尽）；**堆占用 70.9%~87.2% 震荡有界**（年轻代正常更替，无向 OOM 爬升），YGC A 539→2282 / B 367→1616（~50 次/分恒定），**FGC 全程 0**；**段间密度 126~154/s 稳态平直**（首段 warmup 追赶 180/s 后回落，30 分钟无随时间退化）→ 无泄漏型慢漂。结论：该配置（双 admin + 共享 DB 饱和）可持续无故障长跑，调度正确性（status=3=0）与进程健康（堆/线程/连接/FGC）双达标。

### Phase 8 收获（故障恢复：饱和中途强杀 admin A）

**P10 实测（executor 侧 failover + 巡检兜底，2026-09-02 13:51~14:01）。**
P8b/P9 同批次（1-3000，D=300 纯快任务，K=10）双 admin 打满饱和稳态后，**13:55:22 taskkill /F 强杀 admin A（8080，PID 18712）**，B（8082，PID 28060）单独接管 235s 后由 B drain（3 轮收敛，F2-9 复活 58 个清 0）。冻结态 DB 双窗口核验：**pre-kill [13:52:33~13:55:22) 22216 行 / 131.5/s、post-kill [13:55:22~13:59:17) 33485 行 / 142.5/s，两窗 status 全 {1}、distinct 全 3000**；drain 后全局残留 status=0 = 0。采样点确认：kill 即刻 A:8080 拒连、B:8082 200；post-kill 各点密度 116~196/s、st3_60s 全 0、executor registry 全程在线（心跳广播只剩 B 收）。

**F8-1：executor 侧 failover 零丢失（核心验证通过）。**
A 在 ~145/s 饱和中被强杀，executor（AdminAddressPool 广播注册 + failover 回调）自动切到 B：注册心跳照常（registry update_time 新鲜、online 保持 1）；A 死亡瞬间 in-flight 分发全部到达 executor 并经 B 收回调 → **全窗 status=1、无一条残留 status=0**（A 的 insert→/run 投递窗口仅 ms 级：kill 时刻在途日志要么已到 executor 走 B 回调收口、要么根本没产生）。巡检兜底因此**无对象可扫**——回调 failover 把"admin 单点死亡"场景的兜底需求降到零，这正是设计意图（兜底只留给"executor 真没跑成且回调不可能到达"的情形）。

**F8-2：B 单机接管无缝且密度反超（无吞吐悬崖，呼应 F6-1）。**
post-kill B 单机 **142.5/s ≥ pre-kill 双 admin 131.5/s**。F6-1 已证双 admin 的共享 DB claim/decide 行锁竞争是净税（~85 次/s 败者锁等待）；A 死亡恰好移除该竞争 → B 回到单 admin 速率（对照 P4b 单 admin ~149.6/s 同量级）。failover 无吞吐代价，切换瞬时无空洞、无重复、无误判。

**F8-3：claim-未投递点随进程消亡丢一拍（瞬态，非故障）。**
distinct60 在 kill 后 ~57s 采样点 3000→2986（14 个任务单窗静默），下一采样点自愈回 3000。机制：A 已 claim（行锁内推进 next_time）但尚在自身时间轮/触发队列未投递的触发点，随进程强杀一并消失；B 的 catch-up 扫描接手后续点，跳过的仅一拍（~10s），处于饱和态既有"约半拍跳"语义内（p50 间隔 ~20s）。无重复执行、无误判终态、无残留——正确性不受影响。

**F8-4（环境/部署注意事项）：JobLogTimeoutScanner 巡检 SQL 时区耦合。**
巡检 `trigger_time < NOW() - INTERVAL (60|j.timeout) SECOND`（JobLogMapper.selectTimeoutLogIds）用 MySQL `NOW()` 与 JVM 写入的 LocalDateTime 比较。本压测 MySQL 容器时钟 UTC、admin JVM 上海时区，差 8h → **该环境有效巡检阈值从 60s 变成 ~8h**（与观测 SQL 需 `DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)` 同源，见文首环境基线）。本轮回调 failover 已解决全部日志、兜底无对象，故未暴露问题；但**若真实部署 MySQL 会话时区 ≠ JVM 时区，60s 巡检会静默退化成数小时**——部署需确保 DB 与 JVM 时区一致（产品代码无 bug，属配置敏感点）。巡检机制本身（30s 频率、status=3「执行超时未收到回调，结果未知」+ handle_time 统一时间）在时区对齐时正确。

**F8-5（核验澄清）：恢复后 env 检查的 online_executors=0 为查询伪影，非 registry 失效。**
A 重启（tools/launch_adminA.cmd，新 PID 26048）后最终 env 核验曾见 online_executors=0（60s 窗查询），疑似 executor 心跳停摆，复查排除。证据链：① 手动 POST `/registry` 到 **B:8082**（参数同真实 executor）返回 code=200 且 heartbeat_time 推进 → B 写路径正常；② 插入一条 heartbeat_time=5min 前的假 registry 行，**20s 内被 RegistryCleaner（90s 阈值，fixedRate=10s）删除** → cleaner 存活；③ 真行（id=1）在 A 死亡窗口（~8min >> 90s）**从未被删** → 由 cleaner 逻辑反推：该行 heartbeat_time 从未真过期，心跳一直在被存活 admin（B）收到 → executor→B 注册广播在窗口内持续工作；④ 修正时区对齐（`heartbeat_time >= DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR) - INTERVAL 60 SECOND`）复测 **online=1**。结论：早前 0 是查询时区/窗口对齐伪影（或 <90s 的瞬时间隙，未触发 cleaner、自愈），**F8-1「注册心跳照常、online 保持 1」判定成立**，无需产品代码改动。

**Phase 8 未跑分支**：executor-kill 已在 Phase 9（P11）补跑完成（见下 §Phase9），本分支关闭。

### Phase 9 收获（executor 故障恢复：kill executor 小批量）

**P11 实测（dispatch 到死地址 + RegistryCleaner 下线 + 重启重注册，2026-09-02 14:43~14:49）。**
双 admin（A=8080/26048、B=8082/28060）存活下 **14:43:32 taskkill /F 强杀 executor（8081，PID 13164）**，对禁用 batch 任务（1-3000 的 loadTestHandler，SINGLE/retry=0）**确定性手动触发 91 次分四窗**，executor 离线 5.7min，结束全局残留 status=0 = 0：

| 窗 | 时刻 | 触发 | 落账 |
| --- | --- | --- | --- |
| 基线 executor 在线 | 14:43:23 | id 1 | status=1（回调成功，链路正常） |
| 窗口 A executor 死·registry 未清 | 14:43:38~40 | id 11-40（30） | 全 status=2 handle_code=500「I/O error on POST request for "http://127.0.0.1:8081/run": Connection refused」，2.1s 全落账 |
| RegistryCleaner 下线 | 14:44:56 | - | registry 行移除：最后心跳 14:43:24 + 92s（90s 阈值 + 10s 扫描节拍） |
| 窗口 B executor 死·registry 已空 | 14:45:02 | id 51-80（30） | 全 status=2 handle_code=NULL「无可用执行器」，1.2s 全落账 |
| 恢复 executor 重启 | 14:49:13~16 | boot 2.0s，@PostConstruct 广播重注册（新 PID 27576） | registry 恢复 online=1 |
| 恢复后验证 | 14:49:27~28 | id 91-120（30） | 全 status=1 handle_code=200，dispatch 恢复 + 回调闭环 |

**F9-1（dispatch 到死 executor = 快速明确失败，不悬空）：** connection refused（非超时）→ 允许重试（本批 retry=0 则一次）→ 落 **status=2 明确失败**、handle_code=500。关键语义来自 dispatch() 三态设计：只有**超时**才落 status=3「结果未知」且不重试（防重复执行）；connection refused 意味着 handler 根本没跑，快速失败无副作用。→ **executor 宕机时不会产生残留 status=0 孤儿**（每次 dispatch 同步落 status=2），巡检兜底自然无对象可扫（与 F8-1 admin 死同因）。这是本场景正确性核心：调度不会因等待死 executor 的回调而永久悬空。

**F9-2（两相落账可区分）：** registry 未清时 route 仍回死地址 → handle_code=500 + I/O error 文本；registry 被 cleaner 清空后 route 返回 null → handle_code=NULL +「无可用执行器」。运维可据此区分「执行器宕机尚未感知」vs「已感知下线」；handle_code 500 vs NULL 是天然判别位。

**F9-3（RegistryCleaner 在线移除实测）：** 最后心跳 14:43:24 + 92s = 14:44:56 行被移除（90s 阈值 + 10s cleaner 节拍）。此前 cleaner 只经插假行测试验证过删除动作，本次是**真实 executor 宕机场景的端到端下线**。

**F9-4（重启自动恢复，零人工编排）：** executor 重启 boot 2.0s，@PostConstruct 触发 register 广播到双 admin → registry 立即恢复、dispatch 随即可用。executor 离线 5.7min 内所有投递都走 F9-1/F9-2 的正确失败路径，无悬空、无重复执行、无残留 status=0。

**Phase 9 未覆盖（环境限制，非产品缺陷）：** ack 后 executor 中途崩溃（job 已受理 status=0、回调永不达）→ 该 status=0 孤儿本应由巡检 60s 兜底收 status=3「执行超时未收到回调」，但本环境巡检阈值时区错位 ~8h（F8-4）窗口内不可见，需 DB 与 JVM 时区一致的部署环境单测。「dispatch 失败侧」（本档实测闭环）与「ack 后崩溃侧」（巡检兜底设计意图，机制早前 timeout 场景已验）是两条独立路径。

---

## 4. 结论汇总（Phase 1~9 全部完成后）

**一句话总评**：ww-job 经 P1~P11 共十余档负载完成了容量定位、正确性红线验证与故障恢复实测。**正确性（不丢、不重、SINGLE 防重叠、宕机不悬空）在所有负载形态下成立；调度吞吐 ~150/s 级（单/双 admin）是"每触发 3 次事务 commit × ~10 次 DB 往返"的写放大，被 fsync / 连接池 / 触发线程池 / 共享行锁换着方式卡住的结果**。按 plan §0 纪律：容量结论以"相对定位"为主（本机单机、未调优、无真实网络分区），绝对数字仅供同架构相对参考。

### 4.1 正确性红线（全部通过）

| 主张 | 证据 | 结论 |
| --- | --- | --- |
| 触发不丢 | 各饱和档冻结态全窗 status 统计 + distinct=任务数 | 成立：全档 distinct=N，无静默丢任务 |
| 不重复执行 | SINGLE 并发 100×同 job → 恰 1 分发 + 99 被阻塞 | 精确成立（F5-1） |
| 同秒双 claim 竞态 | 修复前 33 条同秒双触发 / 修复后决定性 SQL = 0 | 消除（P8b，F6-2） |
| 失败端到端 | 5% fail handler → status=2 全对 + handleMsg | 正确（F4-3） |
| executor 宕机不悬空 | dispatch 到死地址 → 快速 status=2、无 status=0 孤儿 | 正确（F9-1） |
| admin 死零丢失 | 强杀 A，pre/post 窗全 status=1、无残留 | 成立（P10/F8-1） |

### 4.2 容量与瓶颈链（本压测最核心结论）

瓶颈链逐墙坐实：**binlog fsync（~30/s，F2-1）→ redo-log fsync（~88-95/s，F2-6）→ HikariCP 连接池 10 × ~10 往返/触发（~120/s，F2-8）→ 触发线程池 24（~150/s，P4b/F2-8修订）→ 双 admin 共享行锁串行化（~142/s，F6-1）**。

统一根因 = **调度写放大**：每触发 3 次独立事务 commit（job_log 插入 / job_info.next_time 更新 / 回调更新）、~10 次 DB 往返 —— 同一杠杆在 fsync 墙、连接池墙、线程池墙、行锁墙下换着方式当瓶颈。**这是 ww-job 最核心的性能话题**：要突破 150/s 级需产品改造（触发线程池/连接池可配化、压缩每触发的 DB 往返、或分片/CAS 去锁），单纯调参只能在当前墙内挪动。

两类容量维度独立（容量 = 负载画像的函数）：
- **快任务**：受 admin 侧写放大限制（单 admin ~150/s，调连接池/触发池）；
- **慢任务**：受执行器「线程数 ÷ 任务时长」限制（24 线程 ÷ 2s ≈ 12/s，**与触发 D 无关**，调执行器池或加实例）。

### 4.3 集群扩展性（对照结论）

双 admin ≈ 单 admin（142.5 vs 149.6/s）**不扩展**：触发线程、连接池翻倍而吞吐纹丝不动，墙在共享 DB 的 `claimNextTime`/`decide` 行锁串行化（稳态 ~85 次/s 败者锁等待，单 admin 趋近 0）。**纵向往 admin 加实例无法突破共享 DB 调度写放大**；扩展方向 = 按 job 分片让每 admin 只 claim 自己的分片（消败者等待），或 CAS claim（`UPDATE ... WHERE next_time=旧值`，0 行即放弃）。

### 4.4 稳定性

30 分钟持续饱和（P9）：全 status=1、status=3 全程 0、distinct=3000、线程 A85/B74 恒稳无泄漏、FGC=0、堆 71~87% 震荡有界、MySQL 连接 59~62 无耗尽。**修复零复发 + 无线程/连接/GC 漂移，可长时间跑**。

### 4.5 故障恢复

| 场景 | 结果 |
| --- | --- |
| **admin 强杀**（P10） | executor 心跳/回调 failover 到幸存 admin，零丢失；B 单机接管密度反超（行锁竞争税消失）；claim-未投递点丢一拍瞬态自愈 |
| **executor 强杀**（P11） | dispatch 快速明确失败 status=2（无孤儿）、两相落账可区分（Connection refused vs 无可用执行器）、RegistryCleaner 90s 在线移除、重启 2s 自动重注册恢复 |

巡检兜底（status=3「执行超时结果未知」）**只留给"ack 后 executor 崩溃、回调永不达"路径**——该路径本环境因巡检 SQL 时区错位（8h 阈值）无法窗口内实测，机制已在早前 timeout 场景验证，部署时确保 DB 与 JVM 时区一致即可窗口内闭环（F8-4）。

### 4.6 混合 / 失败 / 告警

- **队头阻塞**：30% 慢任务混入让整体降速 33%（98 vs 146/s，F4-1）——慢任务霸占执行器线程、快任务排队被连带拖慢；建议慢任务专用执行器/分组隔离（当前所有 handler 共池，混合是最差画像）。
- **SINGLE 防重在慢任务高密度下成常态丢单**（status=3 最高 13%，F3-2）：主动防重非丢任务，但日志/前端静默，建议把「被防重丢弃」与「真实失败」分开展示、慢任务建任务时提示增大 cron 间隔。
- **告警静默缺口**：未配 alarmConfig 的任务失败零告警、监控页无记录（F4-3），建议至少打 skip 审计日志或详情展示「已/未告警」状态。

### 4.7 压测中发现并修复的产品缺陷

| 缺陷 | 状态 |
| --- | --- |
| P0-1/2/3：TimeWheel 线程安全、触发无超时阻塞、路由策略失效（早期审查） | 已修复 |
| F2-9：高负载下 stop 被 in-flight 触发旧实体 `updateById` 整写回「复活」 | 已修复（写回收敛，`triggerStatus` 不再被旧对象覆盖） |
| F6-2：claimNextTime 秒边界竞态 → 同秒双 claim（非 SINGLE 任务 = 毫秒级连发两次的真实重复执行风险） | 已修复 commit 44e0b16，决定性 SQL=0 |
| F4-3：告警对空 alarmConfig 完全静默 | 产品建议，未改（需产品决策） |

### 4.8 压测方法论沉淀（可复用于后续场景）

- **秒精度陷阱**：trigger_time/handle_time 秒精度 → P99 须区分「调度延迟」vs「执行耗时」（F1-1/1-3）。
- **时区对齐**：MySQL 容器 UTC vs 应用上海 +8h，所有时间过滤必须 `DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)` 显式对齐（F1-2/F8-4）。
- **分页漂移**：负载进行中 OFFSET 分页拉数虚高 19-21% → 窗口统计必须 drain 冻结后跑（F4-4）。
- **单变量 A/B**：环境性墙（fsync）用单变量翻转确认根因，不拿笼统默认值当结论（F2-2）。
- **stop 不可靠**：档间清理必须循环「停全量→等 5s→重扫验 0」收敛（F2-9）。
- **.cmd 编码**：Windows 批处理脚本避免中文/全角括号（GBK 误解析，Phase 9 教训）。

### 4.9 容量参考（单机、未调优、仅供参考）

| 形态 | 快任务吞吐 | 说明 |
| --- | --- | --- |
| 单 admin，pool=30，双 fsync 关 | ~150/s | 墙=触发线程池 24 |
| 双 admin 同配置 | ~145/s | 不扩展，墙=共享行锁 |
| 默认持久化（sync_binlog=1）单 admin | ~30/s | 第一道墙 binlog fsync（F2-1）——部署先定持久化级别再谈容量 |
| 慢任务 param=2s 单 executor | ~12/s（24÷2s） | 与 D 无关，墙=执行器池 |
| 混合 70/20/10 | ~98/s | 队头阻塞（F4-1） |

**收尾状态**：Phase 1~9 全部归档（P1~P11 行 + §2 详情 + §3 收获）。环境已恢复（A=8080/B=8082/executor=8081 三进程在线、registry online=1、batch 全 disabled、job_log 无 status=0 残留）。测试工具与本文档未提交 git，待用户决定。
