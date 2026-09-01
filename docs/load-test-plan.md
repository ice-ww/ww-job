# ww-job 单机压力测试方案

> 目标：在一台电脑上定位 ww-job 调度系统的瓶颈层，并测量**单实例 / 多 admin 集群下"最多能同时运行的任务数"与触发吞吐**的相对量级。
> 约束：单机（Windows 11）。结论以"相对定位"为主，绝对容量数字必须标注"本机配置、单机、未调优、仅供参考"。
> 关联：`docs/backend-review.md`（已知风险）、`docs/bug-fixes-review.md`（已修复问题）、各设计 spec。

---

## 0. 单机压测能回答什么 / 不能回答什么

**能可靠回答**（结论不受机器影响）：
- 瓶颈在哪一层（调度层 / 执行层 / 数据库写放大 / 网络）；
- 单 admin vs 集群是否扩展、`claimNextTime` 行锁是否成为竞争点；
- 哪个配置参数是敏感项（超时、线程池、连接池、预读窗口）；
- 拐点在哪一档负载出现。

**不能直接回答**：生产环境的绝对容量（单机资源与生产不同、无真实网络分区）。面试话术见 §10。

---

## 1. 目标与判定标准

### 1.1 测量对象

| 指标 | 定义 |
| --- | --- |
| 触发吞吐 | 每秒实际触发的任务数（= job_log 每秒插入量） |
| 同时运行任务数 | **status=0（待执行/执行中）的 job_log 峰值**（异步模型下直接反映并行执行实例数） |
| 成功率 | status=1 占比 |
| 未知态占比 | status=3（超时/被阻塞/未知）占比 |
| P99 延迟 | 触发到执行完成的延迟 |

单位换算：**每秒触发数 × 2 ≈ 每秒 DB 写数**（每触发 ≈ 1 次 job_log 插入 + 1 次 job_info.next_time 更新）——这是预计的写放大来源。

### 1.2 容量上限判定（三条件，任一不满足即拐点）

1. **正确性**：成功率 ≥ 99.9%，且 **status=3 占比不随负载恶化**（status=3 上升 = 静默丢任务，比 CPU 打满更危险）；
2. **延迟**：P99 稳定（本方案按 < 1s 参考，可按业务调整）；
3. **无积压**：触发线程池队列、executor 执行队列、时间轮内任务数不单调增长。

容量上限 = 拐点**上一档**的负载。

---

## 2. 环境准备（单机版）

### 2.1 部署拓扑（全部本机，端口错开）

| 角色 | 端口 | 说明 |
| --- | --- | --- |
| admin A | 8080 | 主调度中心 |
| admin B | 8082 | 集群对照（Phase 6 才起） |
| executor A | 8081 | 执行器 |
| executor B | 8083 | 可选，Phase 6 扩展 |
| MySQL（docker） | **3307** | 独立压测库 `ww_job_loadtest` |
| 原生 MySQL | 3306 | 本机开发库，压测**不碰** |

### 2.2 启动步骤（命令清单）

**① 起压测专用 MySQL**（映射 3307，避开本机 3306）：

```bash
docker run --name ww-job-loadtest-mysql \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=ww_job_loadtest \
  -p 3307:3306 -d mysql:8
```

**② 建压测专用配置** `ww-job-admin/src/main/resources/application-loadtest.yml`（仅覆盖 datasource，指向 3307 压测库；schema.sql 由 admin 启动时自动建表）：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3307/ww_job_loadtest?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: root
```

> root/root 是 docker 默认值（README 已公开），非敏感信息。不要在这里写本机真实密码。

**③ 先装依赖模块**（避免 `-am` 把根聚合模块带进 `spring-boot:run` 的坑）：

```bash
mvn -q -DskipTests install
```

**④ 起 admin**：

```bash
mvn -pl ww-job-admin spring-boot:run -Dspring-boot.run.profiles=loadtest
```

**⑤ 起 executor**（samples，8081；确认 `application.yml` 里 `wwjob.executor.admin-addresses` 指向 `http://localhost:8080`）：

```bash
mvn -pl ww-job-executor-samples spring-boot:run
```

> 记录本机 CPU 核数、内存、JDK 版本、MySQL 容器配置，结果里标注。

### 2.3 关干扰项（压测期间）

- 关闭 IDEA 后台索引、浏览器、Windows Defender 实时扫描（或把 Java 相关目录加排除）；
- 建任务一律 `retryCount=0`、`blockStrategy=SINGLE`（否则"同时运行任务数"无法定义）；
- 告警收件人留空（`alarm_config` 不配），避免压测真发邮件；
- admin/executor 的 JVM 堆压到合理值（如 `-Xmx512m`），贴近"内存敏感"且避免 OOM 拖垮整机。

### 2.4 Windows 端口调优（压测前必做）

大量短连接（触发/心跳/回调）会很快耗尽 Windows 默认动态端口（约 16k）：

```
netsh int ipv4 set dynamicport tcp start=20000 num=40000
```

---

## 3. 压测 handler 规范（你写代码，先定规范）

samples 模块新增 `LoadTestHandler`，注册为 `@JobHandler("loadTestHandler")`：

- **无副作用**：不写库、不发消息，只做纯计算 + 可选 sleep；
- **param 语义**：
  - 空 / `0` → 快速任务（<5ms）；
  - 正整数 → sleep 该毫秒数（如 `100`、`2000`）；
  - `fail` → 抛异常（用于失败比例场景）；
- 返回 `ReturnT.success`；
- 注意：慢任务会真实占用执行线程池，这正是要测的现象，慢任务比例只在混合场景（Phase 4）里加入。

参考实现（示意，按项目现有风格写）：

> 注意：`IJobHandler` 唯一方法签名是 `ReturnT<String> execute(JobContext ctx)`（只有一个参数），
> **param 通过 `ctx.getExecutorParam()` 获取**（看现有 `DemoHandler`），shard 同理 `ctx.getShardIndex()/getShardTotal()`。

```java
@JobHandler("loadTestHandler")
public class LoadTestHandler implements IJobHandler {
    @Override
    public ReturnT<String> execute(JobContext ctx) {
        String param = ctx.getExecutorParam();          // param 从 JobContext 取
        if ("fail".equals(param)) {
            throw new RuntimeException("load test fail");
        }
        int ms = 0;
        try { ms = Integer.parseInt(param); } catch (Exception ignored) {}
        if (ms > 0) {
            try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        long sum = 0;
        for (int i = 0; i < 1000; i++) sum += i; // 防 JIT 优化掉
        return ReturnT.success("load test ok");
    }
}
```

---

## 4. driver：批量建任务，精确控制触发密度

### 4.1 密度公式

目标密度 **D（次/秒）**，每任务触发间隔 **K（秒）** → 任务数 **N = D × K**，任务 i 的 cron：

```
{i % K}/{K} * * * * ?
```

秒位 `i % K` 保证同一窗口内各任务错开触发，**无扎堆**。
示例：D=100、K=10 → N=1000；D=1000、K=10 → N=10000。

> 注意：N 越大，"建任务"本身耗时越长（每个任务一次 INSERT），且 job_info 大表本身也是压测对象，属预期。

### 4.2 参考脚本 `loadgen.py`（Python + requests）

```python
import requests, sys, time

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
D = int(sys.argv[2]) if len(sys.argv) > 2 else 100   # 目标密度 /s
K = int(sys.argv[3]) if len(sys.argv) > 3 else 10    # 每任务触发间隔 s
GROUP = int(sys.argv[4]) if len(sys.argv) > 4 else 1 # 分组 id，见 /jobgroup/list
PARAM = sys.argv[5] if len(sys.argv) > 5 else ""     # handler param（时长/失败）

r = requests.post(f"{BASE}/auth/login",
                  json={"username": "admin", "password": "admin123"})
token = r.json()["data"]["token"]
H = {"Authorization": f"Bearer {token}"}

N = D * K
for i in range(N):
    cron = f"{i % K}/{K} * * * * ?"
    body = {"jobGroupId": GROUP, "handlerName": "loadTestHandler", "cron": cron,
            "routeStrategy": "round", "retryCount": 0, "blockStrategy": "SINGLE",
            "triggerStatus": 1, "param": PARAM}
    r = requests.post(f"{BASE}/job", json=body, headers=H)
    if r.json().get("code") != 200:
        print("fail at", i, r.text); break
    if i % 500 == 0: print(i, "created", time.strftime("%H:%M:%S"))
print("done, N =", N)
```

运行示例：`python loadgen.py http://localhost:8080 100 10 1 ""`

---

## 5. 场景与步骤（按顺序跑）

每档流程：driver 建任务 → **warm-up 1 分钟** → **稳定观测 3 分钟** → 按 §7 记录 → 结束该档（可停任务：`POST /job/{id}/stop`）。

### Phase 0 环境准备
§2 全部就绪，确认 `GET /jobgroup/list` 能看到 `sample-executor` 分组（记住它的 id）。

### Phase 1 基线（快任务）
D=10（N=100，K=10），param 空。记录四层基线指标。

### Phase 2 阶梯递增（找拐点）
快任务，D = **10 → 50 → 100 → 300 → 500 → 1000**，每档 3 分钟。
- 关注：吞吐是否不再随 D 上升（吞吐拐点）、P99 是否陡增（延迟拐点）、status=3 是否抬头。
- 同时记录：admin CPU、DB 写速率、触发线程池活跃/拒绝数。

### Phase 3 最大同时运行任务数（全慢任务）
param=`2000`（全部 2s 慢任务），D = 50 → 100 → 200 → 400 递增。
- 每档统计 **status=0 的 job_log 峰值** = 同时运行任务数；
- 预期：同时运行数随 D×2 上升，直到被 **executor 执行线程池容量**封顶（此后 SINGLE 开始阻塞，status=3 上升）；
- 封顶值 ×（D、线程池容量、SINGLE）三者关系写进结论。

### Phase 4 混合任务
D=300：70% 快(0ms) + 20% 中(100ms) + 10% 慢(2000ms)（用不同 param 各建一批）；
再单独一档 D=300 含 5% `fail`（观察失败对重试/告警的影响）。
- 注意特性：慢任务会把 cron 有效间隔拉长（`advanceNextTime` 按完成时刻推进），实测密度会低于配置——属预期，**记录实际密度而非配置密度**。

### Phase 5 并发手动触发
对单个任务同毫秒并发 `POST /job/{id}/trigger` 100 次。
- 预期：1 条分发 + 99 条 status=3 被阻塞（SINGLE 互斥）；同时观察 DB 行锁竞争（慢日志 / PERF_SCHEMA）。

### Phase 6 集群对照
起 admin B（8082，同 loadtest profile 同库），在同一档 D（300 / 500）重跑 Phase 2。
- 对比单 admin 吞吐，判断扩展性与 `claimNextTime` 行锁竞争；
- 如需，起 executor B（8083）看执行侧扩展。

### Phase 7 稳定性
在 Phase 2 判定的"可通过最高档"附近跑 **30 分钟**。
- 观察：内存漂移、线程数漂移、DB 连接池耗尽、job_log 膨胀速度、GC 频率。

### Phase 8 恢复
kill 一台 admin（或 executor），验证 executor 侧 failover 与巡检兜底是否正常（正确性顺带验证）。

---

## 6. 监控指标与采集方法

### 6.1 吞吐主指标（查库，最干净）

```sql
-- 每 10 秒跑一次，平均密度 = count / 10
SELECT status, COUNT(*) FROM ww_job_loadtest.job_log
WHERE trigger_time > NOW() - INTERVAL 10 SECOND GROUP BY status;
```

status 语义：0=执行中/待回调，1=成功，2=失败，3=超时/被阻塞/未知。
**status=3 占比上升 = 静默丢任务，是正确性红线。**

### 6.2 每进程资源（PowerShell，每 10s 采样）

```powershell
Get-Process java -ErrorAction SilentlyContinue |
  Select-Object Id, @{n='CPU(s)';e={[math]::Round($_.CPU,0)}},
                @{n='MemMB';e={[math]::Round($_.WorkingSet/1MB,0)}} |
  Sort-Object Id | Format-Table
```

CPU 是累计值：**两次采样差值 ÷ 间隔 = 占用率**。必须按进程拆开看，才能定位瓶颈在 admin 还是 executor 还是 MySQL。

### 6.3 JVM / GC（JDK 自带 jstat）

```
jstat -gcutil <admin_pid> 1000
```

关注 YGC/FGC 频率与占比。

### 6.4 跳拍检测（正确性红线）

均匀密度下，job_log 每秒条数若出现"空秒"（本应 >0 却为 0），说明调度侧**跳拍 / 漏入轮**（本项目已知问题，高负载下更易触发）。用 6.1 的 SQL 每 1 秒采样一次即可发现空秒，空秒数计入结果表。

### 6.5 DB 侧

```sql
SET GLOBAL slow_query_log = ON;
SET GLOBAL long_query_time = 0.5;
```

慢 SQL + 行锁等待（PERF_SCHEMA，进阶）用于定位写放大瓶颈。

---

## 7. 结果记录表（每档填一行）

| 档位(D) | N | 实测密度/s | 成功率% | status3% | P99(ms) | admin CPU% | DB写/s | 触发线程池活跃/拒绝 | 空秒数 | 同时运行峰值 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |

说明：
- **实测密度** = 10s 窗口 job_log 插入数 ÷ 10；
- **P99**：抽样 job_log 的执行耗时（handle 相关字段）计算，或人工抽样；
- **触发线程池活跃/拒绝**：Phase 2 中 admin 日志/Actuator 观测，AbortPolicy 触发即线程池到顶；
- **同时运行峰值** = status=0 的 job_log 最大 count（Phase 3）。

---

## 8. 瓶颈定位方法

**拐点法 + 分层归因**（瓶颈出现时按顺序排查）：

| 现象 | 归因 |
| --- | --- |
| admin CPU 满、DB 空闲 | 调度计算 / 线程切换 / 时间轮成为瓶颈 |
| DB 慢 SQL / 锁等待上升、CPU 空闲 | **写放大瓶颈**（job_log + next_time 每触发 2 次写） |
| 触发线程池队列积压 / 拒绝 | 触发线程池容量上限 |
| executor CPU 满 | 执行侧瓶颈（handler 计算）→ 加 executor / 分片 |
| 出现"空秒" | 时间轮 / 预读窗口边界（跳拍） |
| GC 频繁 | 对象分配（日志 / 回调 / TriggerParam） |

**容量上限** = 拐点上一档负载（§1.2 三条件判定）。

---

## 9. 结果解读

- 相对结论可信：**瓶颈层、单 vs 集群扩展性、配置敏感性**——决定它们的是调度模型与 DB 写放大，不是机器；
- 绝对容量只在本机 + 该配置下成立，结论里必须写清楚；
- 把"同时运行任务数"归一成"每秒触发数"和"每秒 DB 写数"，便于与集群/不同配置对比；
- 同档复跑 2 次，排除抖动误判。

---

## 10. 面试话术（被问到压测结论时）

> "我在单机环境压的，绝对容量数字只能参考（本机配置 + 未调优），但瓶颈定位和相对扩展性结论是可信的——因为决定瓶颈的是调度模型和数据库写放大，不是机器。实测验证了：快任务下吞吐到 X 档出现拐点，瓶颈在 Y（DB 写放大 / 触发线程池）；全慢任务下同时运行任务数被执行线程池封顶在 Z；两台 admin 同库跑同一档，吞吐没有翻倍，`claimNextTime` 行锁出现竞争——这解释了集群扩展性边界在哪。"

---

## 11. 注意事项汇总

1. 别碰本机 3306 开发库，压测库独立（3307）；
2. 压测前确认被测分支是**异步回调模型**（同步模型压出来是另一个量级）；
3. 慢任务会拉长 cron 有效间隔，解读时用**实测密度**；
4. **status=3 上升 = 静默丢任务**，即使 CPU/内存没满也要当瓶颈处理；
5. 连接池默认值是坑：DB 连接池、RestTemplate 连接池、执行线程池先确认配置再压；
6. 先 warm-up 再测，JIT 冷启动数据不计入；
7. Windows：端口动态范围先调优；压测时关 IDEA/杀软/浏览器；
8. 多 admin 压测注意 MySQL `max_connections`，锁等待看 PERF_SCHEMA。

---

## 12. 收尾清理

- 停掉所有任务（`POST /job/{id}/stop` 或直接删库最省事）；
- `docker stop ww-job-loadtest-mysql` / 保留容器下次复用；
- 删除 `application-loadtest.yml` 或留作后续复用；
- 可选恢复动态端口设置。
