# P1 Stage B 设计：claim+decide 合并单事务 + 在线执行器内存缓存（cron 7 → 5）

> 子 spec。父：`docs/superpowers/specs/2026-09-03-p1-write-amplification-design.md`（§阶段B 给方向，本文细化并钉死次序/语义）。
> 前提：阶段 A 已验收（P12，D=300 A/B：167/s vs 150/s，写放大收敛，commit 见父 spec F10-1 标注）。
> 用户拍板（2026-09-04）：B1+B2 一个 Stage；协作模式不变 —— ice-ww 自研生产代码，本 spec 只定语义、结构建议与红绿灯；Claude 写测试工具、执行回归与 A/B。

## 0. 对账：A 的"5.0"是 probe 手动口径，cron 热路径基线是 7

**必须钉死的基线事实**（决定 B 的目标账目与 A/B 可比性）：

`tools/probe_write_amp.py` 数的是**手动触发**路径：无 claim 事务、无 advanceNextTime；core 只算 `job_info`+`job_log`（2 读 + 1 写 + 1 INSERT + 1 回调窄更新 = 5），`job_registry` 单独列、不计入"5.0"。

**cron 快任务成功路径（SINGLE 快，retry=0）逐代码账目**：

| # | 语句 | 事务 | 归属 |
|---|---|---|---|
| 1 | claim `selectByIdForUpdate(job)`（行锁 1） | tx#1 | cron 特有 |
| 2 | claim `advanceNextTime`（边界推进） | tx#1 | cron 特有 |
| 3 | decide `selectByIdForUpdate(job)`（行锁 2） | tx#2 | 二次取锁 |
| 4 | decide `countRunning`（SINGLE gate） | tx#2 | 必须 |
| 5 | decide route `SELECT job_registry`（在线地址） | tx#2 | **B2 可归零** |
| 6 | decide `INSERT job_log`（running 带地址） | tx#2 | 必须 |
| 7 | dispatch ack `touch_last_time`（autocommit） | — | 必须（F2-9 窄更新） |
| 8 | 回调 `completeById`（executor 异步线程） | — | 独立于触发线程 |

**cron 触发线程热路径 = 7 条 round trip、决策路径 2 个事务 + 1 autocommit**；异步回调 +1 不占触发线程。

**推论（文档修正项，见 §7）**：P12 行"~835 DB往返/s ≈ 5×167"系 probe 手动口径外推，真实 cron 流量 ≈ 167×7 ≈ **1169/s**。A/B 的 **Δ（10→5 同口径）不受影响**，但绝对数 cron 是 7 不是 5 —— Stage B 目标账目与所有声明一律用 **cron=7 为基线**，不得再引用 5.0。

**Stage B 目标**：cron 触发线程热路径 7 →（B1 合并）6 →（+B2 route 走内存）**5**；决策路径 2 事务 → **1 事务**。

## 1. 正确性不变式

**父 spec 不变式 1-7 全量沿用**（F6-2 触发点幂等 / 锁窗口 HTTP 在外 / D3 SINGLE 判-插原子 / F2-9 job_info 窄更新 / D2 迟到回调 / 幂等收账 IN(0,3) / 分片广播独立），外加 B 特有两条：

- **B-1 边界消费次序**：合并事务内 `advance` 必须在 **claimable 判定通过后立即执行、先于 SINGLE gate**。次序钉死为：锁行 → status门（停用则返回 null）→ claimable 门（边界未到/已被别台推进则返回 null）→ **advanceNextTime** → SINGLE gate（阻塞则落 status=4、返回 null）→ route → INSERT running。理由：blocked 触发也必须消费本边界（否则同一过期点永续重试）；现状两 tx 的墙钟语义就是"先 claim 推进、后 decide 判阻塞"。父 spec §B1 草图把 advance 排在 insert 之后只对 running 分支等价，对 blocked 分支会引入同点重试回归 —— 本 spec 显式钉死 advance 先行。
- **B-2 回退可见性**：B2 缓存"组内无新鲜"时必须回退 DB 复核，不得仅凭本 admin 缓存判"无执行器"。保留现状语义：与执行器分区但收到过心跳的 admin 仍能路由；冷启动、静默死亡、executor-kill 后缓存驱逐与 DB 口径逐格一致（90s）。

## 2. B1：claim + decide 合并单事务

### 目标与净效果

合并后 cron（非 sharding）每次触发 = **1 个事务**内完成 {锁行、status/claimable 门、advance、SINGLE gate、route、INSERT running}，HTTP 分发仍在事务外。每触发：少 1 次行锁获取（2→1）、决策路径 2 提交 → 1 提交；净账目 7 → 6（B2 再把 route 归零到 5）。

### 结构（推荐，实现计划细化签名）

decide 的"gate → route → insert → 返回载具"抽成**锁下决策核心**（调用方已持锁、不自身开事务）：

```java
// JobDecisionService —— 核心假定调用方已持 job 行锁，绝不自身开启/传播新事务边界
DecideResult decideUnderLock(JobInfo lockedJob, String triggerType);
// 手动/API 入口保留：自身 @Transactional { selectByIdForUpdate → decideUnderLock }
DecideResult decide(long jobId, String triggerType);   // 现有语义不变（无 claim/advance）
```

cron 合并入口挂在 JobTriggerServiceImpl（当前 claim 属主），自身 `@Transactional`：

```java
// 语义：返回 null = 停用 / 边界未到 / SINGLE 阻塞 / 无执行器（均已落账或无需落账）
// 事务内完成全部决策，commit 后由调用方 dispatch（HTTP 在事务外）
DecideResult cronClaimAndDecide(long jobId, String cron);
// 内部：selectByIdForUpdate → status门 → claimable(nowSec 秒截断)门 → advanceNextTime → decideUnderLock
```

**事务归属**：外层事务在 JobTriggerServiceImpl 的 cron 入口；JobDecisionService.decideUnderLock 必须**无 `@Transactional`**（plain 方法加入外层事务）—— 若带注解形成 REQUIRES join 依赖魔法边界，排除（B1 备选乙，见 §6 记录）。

**调度侧**：ScheduleHelper 时间轮回调由两段（claimNextTime 后 trigger）改为按 routeStrategy 分支：
- 非 sharding → 一次 `cronClaimAndDecide(jobId, cron)`，非 null 则 `dispatch(result, "cron")`；
- sharding → 维持 claim（独立事务）→ broadcast 两步（broadcast 的 `onlineAddresses` 改读 B2 缓存，见 §3），不进合并事务（父不变式 7）。

**分支前提**：合并/广播二选一需要 routeStrategy，取自调度侧持有的 JobInfo（scheduleLoop 每轮 selectList 的新鲜实体）。若现时间轮入轮对象只携带 id+cron 而无 strategy，实现计划须把 strategy（或整实体）一并携带 —— 以锁内读到的 strategy 分支亦可（合并事务内分支后走 decideUnderLock 或广播），两种实现路径等价，选一即可，语义不变。

### 锁窗内容与时长

合并后行锁持有 = select-FU + advance + count + route + insert。其中 **route 在 B2 后为内存读（0 DB），DB 回退仅在缓存空时发生** → 锁窗内实质 DB 工作与今天 decide 锁窗同界（多一行 advance 窄更新）。不做 HTTP、不 sleep、不查无关表。单 admin 下无行锁竞争（P12 实测 ~0.0006/触发 waits），锁窗合并不构成新墙；多 admin 下每次触发行锁获取从 2 次 → 1 次，F6-1 方向的争用税预期下降，但**如实标注：多 admin 增益由后续 P6 场景实测，不预设结论**。

### 行为 delta（与两 tx 现状的差异，红灯记录）

- **少丢火（改善）**：现状 claim tx 已提交、decide tx 失败 → 边界被消费但无日志、该点永久丢失。合并后同场景整事务回滚 → 下一轮调度重试同一迟到边界 → **at-least-once 倾向**。须用 F6-2 决定性 SQL（同 job 同秒双触发 = 0）验证回滚重试不会造成同秒双触发。
- **回滚语义**：事务失败即未推进、未落账，调度侧照常重排（job 仍在 trigger_status=1，下轮 scheduleLoop 会再取）。
- 其余（stop 竞态 status 门、D2、幂等收账、touch）语义零变化，走 §6 回归。

## 3. B2：在线执行器内存缓存（route 0 DB）

### 结构与读/写路径

新 `RegistryCacheService`（admin 侧，无 DB、无新线程，双层 ConcurrentHashMap）：

```java
// jobGroupId → registryValue → 最近心跳时刻（admin JVM 时钟域，与 DB 写入/cleaner 同一域）
ConcurrentMap<Long, ConcurrentMap<String, Long>>  // value → epochMillis
```

- **写侧（3 点注入，全部在既有心跳/清理线程上，零新增线程）**：
  1. `RegistryService.upsert`（`/registry` 注册与 `/heartbeat` 心跳的 DB upsert **成功分支**，计划阶段核对实际落点）→ `cache.touch(groupId, value, now)`；
  2. `RegistryService.offline`（优雅下线删行成功）→ `cache.remove(groupId, value)`；
  3. `RegistryCleaner` 每次清理（现 @Scheduled 10s、删 90s 离线行）→ 顺带扫缓存剔 >90s 项（内存有界，防长期 churn 增长；读侧懒过滤才是权威口径，清扫仅为内存回收）。
- **读侧**：`ExecutorRouterService.onlineAddresses(jobGroupId)`/`route` 先读缓存：组内 ≥1 新鲜（now − 90s 内，语义与 DB `ge heartbeat_time` 阈值一致）→ 直接返回（0 DB）；**组空或全陈旧 → 回退 DB 原 `selectList` 并水合缓存**（B-2）。回退点放"空集"而非"永回退"：单执行器正常态稳态 0 读。

### 语义裁定

- **排序**：route 输出按 registry_value 字典序（跨 admin 实例、跨重启确定性）。仅 FIRST 策略依赖序；多执行器组下 FIRST"具体选哪台"从 DB 行序改为值序 —— 集合正确性不变（选中的仍是在线执行器），单执行器组（压测/回归常态）零差异。写成文档决策，实现计划在 P5/多执行器场景回归确认无断言依赖具体选台。
- **`/registry/list` 与仪表盘在线数仍走 DB**：展示口径 = 共享源，允许多 admin 显示 ≤1 心跳周期（30s）滞后于各自 route 缓存；不并轨（缓存无 DB 行 id、无 update_time，且跨 admin 本就该一致展示）。
- **多 admin 收敛**：每台 admin 缓存由**自己收到的心跳广播**驱动；executor 启动注册 + 每 30s 广播 + 优雅停机 offline（executor 侧 ExecutorRegistry 事实），失联/冷启动 admin 至多滞后一个心跳周期即自愈，此间 route 走 DB 回退 —— 正确性不受损（B-2）。
- **时钟**：缓存存 admin JVM 时刻（与 DB heartbeat_time 写入、RegistryCleaner 阈值同域），不做 DB NOW()/会话时区比较（延续 F8-4 纪律）。
- 冷启动：缓存空 → 首触 route 回退 DB 并水合；无需 @PostConstruct 预热查询。

## 4. 目标账目与诚实预期

| 口径（cron 快任务，触发线程） | pre-B | B1 后 | B1+B2 后 |
|---|---|---|---|
| DB round trip / 触发 | 7 | 6 | **5** |
| 决策事务 / 触发 | 2 | 1 | 1 |
| 锁内 registry 读 | 1 | 1 | **0**（回退除外） |
| 行锁获取 / 触发 | 2 | 1 | 1 |

**诚实预期（写进验收）**：Stage A 靠砍 5 条往返拿到 +11%（150→167）。B 只砍 2 条、本地 MySQL 往返毫秒级 —— **墙可能只移动几个百分点甚至不动**。Stage B 验收重心 = 结构账目达成 + 正确性红线全绿 + D=300 A/B **如实上报**（密度动了多少就报多少）。A/B 附带回答"DB 往返在每触发耗时里到底占多少"这个 A 阶段未直接量的问题。吞吐不是 B 的承诺；**每触发 DB 往返减半 + 决策提交减半**（写放大压缩的本质）才是。

## 5. 非目标（本次不做）

- 手动触发路径优化（无 claim，天然 1 事务，已接近最优）。
- 分片广播并轨 SINGLE gate/行锁（父不变式 7，不做）。
- `job_log(status, trigger_time)` 复合索引、巡检批量收窄（父 spec 非目标，另立项）。
- registry 展示口径并轨缓存、心跳周期/ONLINE_SECONDS 调参（维持现状 30s/90s，不动产品参数）。

## 6. 验证方案（验收门）

### 6.1 回归（B 每步改动后跑，先红后绿）

- `verify_p1_stage_a.py fast`（手动路径零回归，尤其 c1 地址落库/c5 SINGLE status=4）、`slow`（D2）、`cron`（F6-2 无同秒双触发兜底）；
- SINGLE 并发 decisive SQL（Phase5 口径：1+99 重叠 → 恰好 1 条 running，其余 status=4）；
- B 特有：合并事务 **blocked 分支仍推进边界**（SINGLE 重叠后同 job 下个 cron 点照常触发，不永续重试同一过期点）；
- 回滚重试无同秒双触发（F6-2 decisive SQL 复用）；
- 手动触发、无执行器（空地址落 status=2）、stop 竞态、分片广播（在线地址读缓存后仍正常落库）。

### 6.2 cron 口径写放大 probe（新工具，Claude 写）

`tools/probe_write_amp.py` 只测手动、且分类列数与 cron 形状不同，**不可直接套用其列断言**。新工具对 **cron 触发**（SINGLE 快任务，与 D=300 画像一致）开 general_log 窗（一批 ~30 个 1Hz 聚合触发规避单 job 饿死相位），按 `job_log INSERT` 锚定 per-fire，**触发线程**语句普查断言（括号为语句归属）：

| 分类 | per-fire | 说明 |
|---|---|---|
| job_info 读 | 1 | 合并后单次 `selectByIdForUpdate`（两段时代的二次锁消失） |
| job_info 写 | 2 | advance（锁内）+ touch_last_time（ack，锁外 autocommit） |
| job_log | SELECT countRunning 1（SINGLE gate）+ INSERT 1 | 判-插原子在同一事务 |
| job_registry 读 | ≈ 0 | 剔除心跳 INSERT/cleaner DELETE 噪音；冷启动水合不在稳态窗 |
| 触发线程合计 | **5** | + 异步回调 completeById（job_log UPDATE ×1，不占触发线程） |

- general_log 分组显示每 fire **单 BEGIN…COMMIT**（select-FU+advance+count+INSERT 同一事务）取代两段 → 决策 2tx→1tx 直接证据（对照窗：同一探针先跑未合代码，应见两段 BEGIN…COMMIT）。

### 6.3 B2 专项（admin 自研后 Claude 执行）

- **executor kill 驱逐**：kill executor → ≤90s 内缓存与 DB 同语义剔除 → 后续触发落 status=2（无执行器）；重启 executor → 重注册恢复路由。对照现状 DB 语义逐格一致。
- **冷启动回退**：重启 admin → 立即（首个心跳周期内）触发必须成功（走 DB 回退/水合，不得误落 status=2）。
- **双 admin 收敛**（可选，P6 场景）：双 admin 各自心跳驱动缓存收敛一致；F6-2 决定性 SQL。

### 6.4 D=300 A/B（对照 P12）

复刻 P12 配方（enable 1-3000、3min 观察、disable 冻结、双口径互证），单 admin loadtest 环境。上报：密度（触发时间窗 + insert 双口径）、per-sec min/max/空秒、status 分布、同秒双触发、行锁 waits、Hikari conns、DB 往返/s（cron 口径 5×密度）。对照 P12 167/s/210 tail。

## 7. 文档修正项

`docs/load-test-results.md` P12 行"~835 DB往返/s ≈ 5×167"补注脚：probe 为手动口径，cron 实为 7 → 真实 ~1169/s；Stage B 后 cron 口径 5。默认不提交 docs，改完留工作区，由用户决定提交。

## 8. 交付物验收清单

- [x] 阶段 B spec（本文）自审通过、用户 review 通过（2026-09-04，用户拍板 B1+B2 一个 Stage）
- [x] 另立实现计划（writing-plans）`docs/superpowers/plans/2026-09-04-p1-write-amplification-stage-b.md`，任务粒度含每步回归
- [x] **B2 缓存先落地**（ice-ww 自研）：`RegistryCacheService` 双层缓存 + 写侧 3 点（upsert/offline/cleaner）+ 读侧空回退/水合；§6.3 专项全绿（见下 §9 实测记录）
- [x] **B1 合并事务随后**（ice-ww 自研）：decideUnderLock 锁下核心 + cronClaimAndDecide 单事务；§6.1 回归全绿 + blocked 边界消费实锤（`verify_b1_blocked_boundary.py` PASS）
- [x] cron 口径 probe 工具落 `tools/probe_write_amp_cron.py`：实测 FU=1.0 / core=5.0 / registry 读≈0 / 单事务分组（绿）
- [x] D=300 A/B 对照 P12 归档 `docs/load-test-results.md` §Phase11（284.5/s vs 166.9/s = +70%，贴名义上限 ~95%；诚实两轮标注）
- [x] P12 注脚修正（§7）留档 `docs/load-test-results.md` P12 档
- [x] 阶段 B 实测记录回填本 spec §9（父 spec §阶段B 保持设计方向不变）

## 9. 实测记录（2026-09-04 回填，代码 = HEAD ad7b789 + 工作区 Stage B 未提交）

| 验证 | 工具 | 结果 |
|---|---|---|
| cron 口径写放大 probe | `tools/probe_write_amp_cron.py` | core=5.0/触发、route registry≈0、决策单 BEGIN…COMMIT（2tx→1tx 直接证据） |
| B2 executor-kill 驱逐 | `tools/verify_b2_eviction.py` | PASS：kill → ≤90s 缓存/DB 同语义剔除 → 无执行器 status=2（handle_time NULL）；重启恢复路由 |
| B2 冷启动回退 | `tools/verify_b2_coldstart.py` | PASS：admin 重启后首个心跳周期内触发 status=1（空缓存 DB 回退/水合，不误落 status=2） |
| B2 双 admin 收敛 + F6-2 | `tools/verify_b2_dualadmin.py` | PASS：8080/8085 双 scheduler 对称竞争 261 fires，(job_id, trigger 秒) 双触发=0、全 status=1 |
| B1 blocked 边界消费 + SINGLE 互斥 | `tools/verify_b1_blocked_boundary.py` | PASS：blocked 也消费边界、相邻 accepted 间隔≥14s 无并发、next_time 推进不停滞 |
| D=300 A/B 对照 P12 | `tools/verify_b2_ab_p13.py` | PASS（决定性复测）：284.5/s vs 166.9/s（+70%）；status 全1、distinct=3000、同秒双触发 0、行锁 waits Δ=0、DB往返/s≈1423；首轮 229.2/s（+37%）两轮方差见 load-test-results.md §Phase11 |

P12 档注脚（§7 修正）：真实 cron 口径 7×167≈1169/s（非手动口径 5×167≈835）。阶段 B 后 cron=5 为账目口径，见 §0/§4。
