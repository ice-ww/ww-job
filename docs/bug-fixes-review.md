# ww-job 开发复盘：遇到的 Bug 与解决过程

> 2026-08-26 · Phase 1 收尾 + 全量代码审查 + 执行语义修正 这一轮的实际记录。
> 用于面试前复习：每个 bug 都按「现象 → 根因 → 修复 → 验证 → 面试要点」整理。
> 代码基线与 commit：`03a3b44`（P0 安全修复 + 思路一执行语义修正）。

---

## 一、架构与并发层（最值得面试展开）

### Bug 1：调度任务永久失活（时间下界 bug）

**现象**：某个 cron 任务停摆——到了触发点却再也不触发，重启 admin 也救不回来。

**根因**（commit `50f5ef5`）：调度线程预读 DB 时用了两个条件
```sql
trigger_next_time <= now+5s AND trigger_next_time >= now
```
问题出在 `>= now` 这个**时间下界**。一旦任务因为任何原因落后（admin 重启、上次推进失败、或执行耗时跨过了原定触发点），它的 `trigger_next_time` 就成了过去时，永远不满足 `>= now`，于是**再也捞不起来**——任务永久失活，且没有任何报错，很难发现。

**修复**：预读去掉时间下界（只保留 `<= now+5s`），把「落后任务」也捞出来，交给 `scheduleIfNeeded` 处理：如果 `next < now`，先把 `next` 推进到「下一个未来触发点」再入轮——既不立即补触发（避免重启后一顿乱跑），又保证任务不会因过期而失活。

**验证**：重启 admin 后旧任务能继续按 cron 触发；慢任务跨过触发点后依然能续上。

**面试要点**：
- 这类「永久失活」比「崩溃」更难排查，因为**没有错误日志，只是静默地不再工作**。排查思路：把「为什么该触发却没触发」当成首要怀疑点。
- 修复的核心是**语义问题**：查询条件把「正常状态」和「异常状态」混在了一起，导致异常状态无法自愈。加下界看似严谨（只查未来），实际制造了死区。
- 延伸：调度系统里「追跑（catch-up）」是常见话题——错过的时间点要不要补跑、补跑会不会重复，都是面试官爱追问的。

---

### Bug 2：时间轮线程不安全（P0-1）

**现象**：偶发丢任务、重复触发，特定情况下抛 `ConcurrentModificationException`。

**根因**：自研时间轮 `TimeWheel` 的 `slots` 数组和 `currentTick` 指针没有任何同步保护，但有两个线程并发操作：
- `scheduleThread`：每秒预读 DB，调 `addTask()` 写入
- `ringThread`：每秒调 `advance()` 推进指针并取出到期任务

两个线程每秒都在并发读写同一份数据结构，自然存在数据竞争：写入时指针已移走 → 丢任务；同一个 slot 被并发读到 → 重复触发；遍历时被修改 → CME。

**修复**：`addTask` / `advance` 加 `synchronized`，并且**把任务的执行移出锁外**（`advance()` 只取出到期任务列表，真正的 HTTP 触发在锁外由线程池执行）——否则虽然不崩了，但 `synchronized` 会把写和推进串行化，还可能把慢任务执行时间算进锁内，反而卡住写入。

**验证**：双线程持续跑一段时间，任务不再丢、不再重复，无 CME。

**面试要点**：
- **「两个线程 + 一个共享结构」是所有并发 bug 的母体**。动手前先画清楚：谁在写、谁在读、临界区在哪。
- 加锁只是第一步，**锁的粒度（临界区范围）才是关键**：把耗时操作（网络调用）放进锁内，等于把并发打回串行。这是「同步块设计」的经典考点。
- 为什么不用 `ConcurrentHashMap`/`ConcurrentLinkedQueue` 直接实现？可以延伸讨论「数据结构选型 vs 加锁」的取舍。

---

### Bug 3：触发无超时，执行器挂起能冻死整个调度器（P0-2）

**现象**：一台执行器进程假死（Tomcat 线程阻塞、网络不通但连接未断），admin 这边 `RestTemplate` 请求一直挂着不返回。

**根因**：`new RestTemplate()` 默认的连接/读超时是 **0 = 无限**。而触发链路是同步的：ringThread 在时间轮里直接调 HTTP。于是——
- 单次触发无限阻塞；
- ringThread 被卡住后，**时间轮后续所有到期任务全部顺延**——一台执行器挂起 = 整个调度中心瘫痪。

**修复**（两层）：
1. 给 admin 的触发 `RestTemplate` 设 **connect 3s / read 10s** 超时；
2. 给 `ScheduleHelper` 加 `ww-job-trigger` 触发线程池（min 4，核心数×2）——ringThread 只负责**从时间轮出队**，真正的触发（HTTP + DB）丢给线程池。执行器挂起时最多占一个线程池线程，不再卡时间轮。
3. 顺带检查发现执行器的心跳 `RestTemplate` 同样没超时，而 `@Scheduled` 默认单线程——心跳挂起会停摆，执行器被误判下线。一并加上同样的超时。

**验证**：慢任务（sleep 15s）持续触发，调度器其它任务不受影响、时间轮照常推进；心跳恢复后不再误下线。

**面试要点**：
- **「默认值就是坑」的典型案例**：Spring `RestTemplate` / JDK `HttpURLConnection` 超时默认无限，这是所有 HTTP 调用的「新手杀手」。凡是发起对外 HTTP 请求，第一件事就是设超时。
- 这个 bug 暴露的是**「同步模型下的故障隔离」**：一个依赖点失败，不能拖垮整条调度链路。线程池 + 超时 = 把单点故障限定在局部。
- 延伸追问点（今天的思路一就是从这里长出来的）：加了超时之后，`ReadTimeoutException` 到底算「失败」还是「未知」？重试会不会造成重复执行？——见 Bug 5。

---

### Bug 4：路由策略失效，永远选第一台执行器（P0-3）

**现象**：配了 `round`（轮询）路由，但多次触发日志里 `executorAddress` 恒为第一台。

**根因**：路由代码每次调用都 `new` 一个路由器：
```java
Router router = new RoundRobinRouter();  // 每次 route() 都新建
```
`RoundRobinRouter` 内部用 `AtomicInteger` 计数，**新建实例时计数器每次从 0 开始** → `idx` 恒为 0 → 永远选中第一个地址。`random` 策略因为随机分布不明显，`failover` 也因为总是选第一个，问题被掩盖了。

**修复**：把三个路由器提为 service 的**单例字段**，跨调用保持内部计数器；`route()` 只做策略选择和转发。

**验证**：双执行器下轮询触发，日志显示地址在 8081/8082 之间交替。

**面试要点**：
- 这个 bug 的根因不是「轮询逻辑写错」，而是**「对象生命周期与状态保持」**：有状态的对象（计数器）必须单例/持久，否则状态被重置。反问自己：这个对象是有状态还是无状态？有状态就要保证单例。
- 顺带价值：修复后 `failover` 重试会自然轮转到不同执行器，间接缓解了 P1-2（见下）。
- 面试可延伸：轮询/随机/一致性哈希的实现与场景。

---

### Bug 5：同步模型的三个隐患——假失败 / 重试重复执行 / 线程占用（思路一）

这是今天最有价值的一轮，由用户（你）在 review 时主动提出的三个问题：

**问题 1 · 假失败**
- 场景：任务实际执行 30s，admin 读超时 10s 放弃等待。**放弃等待只是客户端行为**——执行器上的 handler 照常跑完，副作用（发消息、写数据）全部发生，但 admin 日志却记「失败」。
- 本质：**「我没等到结果」≠「任务失败了」**。超时是「结果未知」，不是「失败」。

**问题 2 · 重试导致重复执行（非幂等事故）**
- 场景：任务 30s，retryCount=2。t=0 触发 → 执行器开始跑 A；t=10 admin 超时 → 重试 → 执行器**又**跑 A（第一个还在跑）；t=20 超时 → 再重试 → 第三个 A 开跑。**同一任务并行 3 份**。
- 更糟：`route()` 每次重选执行器，failover 策略下任务被撒到多台机器。
- 本质：**at-least-once 语义 + 盲目重试 = 重复执行**。对扣款、发短信这类非幂等 handler 就是事故。

**问题 3 · 线程/时间轮被长时间占用**
- 单次触发最长 ~30s（3 次尝试 × 10s），慢任务持续挤占触发线程池，拖慢整个调度中心。

**修复**（`JobTriggerServiceImpl` + `JobLog`）：
1. **超时不重试**：识别超时（沿异常 `cause` 链找 `SocketTimeoutException`，RestTemplate 会把它包在 `ResourceAccessException` 里）——一旦超时就 `break`，绝不再试。只有「连接被拒绝（handler 根本没启动，重试安全）」或执行器明确返回失败码才重试。
2. **status=3 未知态**：`JobLog` 新增 `STATUS_UNKNOWN=3`。超时/被阻塞落这个状态，`handleMsg` 提示「执行超时，结果未知，勿重复触发」，不再误判成失败（status=2）。
3. **`block_strategy=SINGLE` 互斥**：`runningJobIds`（`ConcurrentHashMap.newKeySet()`）判重——同一任务同一时刻只允许一个实例，上次没结束就直接丢弃本次触发并记一条 status=3「被阻塞」日志，`try/finally` 释放。

**验证**（端到端实测，数据真实）：
- 15s 慢任务 + retryCount=2：15 次分发 → 执行器**恰好跑 15 次**（若无此修复会是 45 次）；17 条日志全部 status=3，0 条 status=2。
- 3 条**并发**手动触发（同一毫秒级）：1 条分发 + 2 条被阻塞丢弃（executorAddress 为空）。
- 过程中发现两个设计特性（非 bug，但值得讲）：
  - cron 有效间隔会被慢任务拉长到 ~15s（`advanceNextTime` 在触发完成后按「完成时刻」算下次）；
  - cron-cron 重叠其实早被 `scheduledJobIds` 去重挡住，SINGLE 真正防的是**手动触发 vs cron** 的重叠。

**面试要点**（这个 bug 最值得背熟）：
- 三个概念要区分清楚：**成功 / 失败 / 结果未知**。超时是第三个状态，不能归到失败。
- 分布式系统的经典三角：**at-least-once / exactly-once / 幂等**。同步模型下要做到不重复执行，要么「不确定就重试」（会重复）、要么「不确定就不重试」（会丢触发）、要么让业务幂等（logId 去重）。本项目选的是「不重试 + 未知态 + 幂等交给业务方」，并且**明确记录了边界**（执行 >10s 的任务跨超时窗口的重叠挡不住）。
- 主动承认方案的边界并给出根治方向（Phase 2 异步回调模型，xxl-job 风格），比装作没有问题加分得多——**面试官想看到的是你对取舍的清醒认知**。

---

### 已知未修的风险（P1/P2，面试诚实加分项）

| # | 风险 | 说明 | 为什么没修 |
| --- | --- | --- | --- |
| P1-1 | 注册中心 upsert 非原子 | `RegistryService` 先 `selectOne` 再 `insert/update`，两步间无锁，`job_registry` 无唯一约束 → 并发心跳可能插入重复行 | Phase 2 配合唯一索引 + `INSERT ... ON DUPLICATE KEY UPDATE` |
| P1-2 | failover 不是真故障转移 | `FailoverRouter` 不探测可用性，死节点 90s 内仍在候选列表 | 单次触发内维护「失败地址排除集」，或引入健康检查 |
| P2-6 | 多 admin 实例重复调度 | 无分布式锁，两个 admin 会把同一任务各触发一遍 | 单机部署无碍；Phase 2 上分布式锁（或把调度做进 admin 集群） |
| 固有 | 幂等依赖业务方 | 同步模型下无法完全去重，业务需用 logId 做去重键 | 文档明确要求；Phase 2 异步回调仍需要业务幂等（所有调度系统都绕不开） |

**面试话术**：审查时主动列出风险清单并分级（P0/P1/P2），先修 P0，P1/P2 记录在 `docs/backend-review.md` 并说明修法。这体现「不是不会出问题，而是**有意识地管理风险**」。

---

## 二、开发环境与工具链层

### Bug 6：`mvn -pl ww-job-admin -am spring-boot:run` 起不来

**现象**：报错 `Unable to find a suitable main class ... on project ww-job`。

**根因**：`-am`（also-make）会把依赖链上的**根聚合模块**（`ww-job`）也带进 `spring-boot:run`，而根模块没有 main class，Boot 插件直接报错。

**修复**：先 `mvn -q -DskipTests install` 把依赖模块装进本地仓库，再 `mvn -pl ww-job-admin spring-boot:run -Dspring-boot.run.profiles=local`（**不带 `-am`**）。

**面试要点**：多模块 Maven 项目的常见坑——`-am` 是「连依赖一起构建」，`spring-boot:run` 只对「有 main class 的应用模块」有意义，两者组合会撞上聚合模块。

### Bug 7：`TaskStop` 杀 mvn，但 java 子进程还占着端口

**现象**：停了 `mvn spring-boot:run`，8080/8081 仍被占用。

**根因**：`spring-boot:run` 会 **fork 一个子 JVM** 真正运行应用。杀掉 mvn 进程，子 JVM 成了孤儿进程继续存活。

**修复**：`netstat -ano | grep 8080` 找到 PID，`taskkill //F //PID <pid>` 按 PID 杀。

**面试要点**：这类问题的通用模式是**「父进程拉起子进程，杀父不杀子」**（Maven/Gradle 的 fork、守护进程、shell 脚本都是）。排查思路：先定位真正持有资源的进程，再对症下药。

### Bug 8：Windows 下 grep 中文日志匹配不到

**现象**：执行器打印的「demo 执行成功」，grep 匹配计数为 0。

**根因**：Windows 控制台/重定向默认代码页是 **GBK**，`System.out.println` 的中文以 GBK 字节写进日志；而 grep 的 pattern 是 UTF-8 编码的「demo 执行成功」，字节对不上，永远匹配不到。输出其实在，只是编码错位。

**修复**：用 ASCII 子串匹配（`grep "demo"`、`grep "logId="`），绕过中文编码。

**面试要点**：**编码是「生产者写字节、消费者读字节」的约定**，两端不一致就乱码。Windows 开发要时刻警惕默认 charset（GBK vs UTF-8）。延伸：`-Dfile.encoding=UTF-8`、`-Dstdout.encoding` 的解法，以及日志框架 vs System.out 的差异。

### Bug 9：`git push orgin main` 拼写错误

**现象**：`fatal: 'orgin' does not appear to be a git repository`。

**根因**：remote 名 `origin` 只是**约定俗成的标签**，不是 git 内置的关键字。拼成 `orgin`，git 把它当成一个叫 `orgin` 的 remote 去查，查不到就报错。**git 不会自动纠正 remote 名字拼写**。

**修复**：用正确的 `git push origin main`。

**面试要点**：讲清楚「remote 名是标签」这一概念（`git remote -v` 查看），顺带展示你理解 git 配置的本质，而不是死记命令。

### Bug 10：真实数据库密码险些入库 public 仓库

**现象**：`application.yml` 里写了真实的本地 MySQL 密码，仓库是 **public** 的，一旦提交推送，密码永久暴露在 GitHub（历史里删不干净）。

**修复**（commit `830be30`）：
1. `application.yml` 默认密码改成 `root`（对应 docker-compose 的默认值）；
2. 真实密码放进 **gitignored** 的 `application-local.yml`；
3. 用 Spring profile `local` 激活：`mvn ... -Dspring-boot.run.profiles=local`；
4. `.gitignore` 里确认 `application-local.yml` / `.properties` 已忽略，`git check-ignore` 验证。

**面试要点**：
- **敏感信息（密码/密钥/token）绝不进代码库**，尤其 public 仓库。Git 历史里的内容是删不掉的——提交前就要检查（`git status` / `.gitignore` / pre-commit 检查工具）。
- 隔离手段分层：代码库给默认值 / 环境变量 / profile 覆盖 / 配置中心（Nacos）——讲出「环境与配置分离」的思路。

---

## 三、方法论沉淀（面试可以直接输出）

1. **复现 → 定位根因 → 最小修复 → 端到端验证**：今天每个 bug 都是这条链路走完的，且 P0 修复后都做了**双执行器实测**而非只编译通过。
2. **并发问题先画线程边界**：谁在写、谁在读、临界区多长、锁外能不能执行耗时操作。
3. **分布式语义三态思维**：成功 / 失败 / 未知，三态必须分清楚；at-least-once 下「重试 = 重复执行」，要么不重试、要么业务幂等、要么异步回调。
4. **默认值是最大的坑**：HTTP 超时默认无限、`@Scheduled` 默认单线程、Windows 默认 GBK——凡是没显式配的，都要怀疑它的默认行为。
5. **有状态对象必须保证单例**：计数器、路由状态这类「跨调用要记住的」，生命周期要和「调用次数」解耦。
6. **管理风险而不是否认风险**：主动分级（P0/P1/P2）→ 先修核心 → 记录余量并给出修法，是成熟的工程习惯。
