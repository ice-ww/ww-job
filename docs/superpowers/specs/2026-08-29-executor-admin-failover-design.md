# ww-job executor 多 admin 故障切换 设计

> 日期：2026-08-29
> 状态：设计已对齐，待实现
> 背景：调度中心已支持多 admin 集群（admin 间共用 DB、行锁幂等），但 executor 侧仍是裸奔：`adminAddresses` 虽支持逗号列表，`ExecutorRegistry`（注册/心跳）与 `CallbackReporter`（回调）的循环都是**第一台失败就整体 abort**——一台 admin 宕机会拖累所有活着的 admin，回调可能永远到不了任何一台。本次给 executor 补上「广播容错 + 回调故障切换」的通信层，支持多 admin 下任意一台宕机不影响执行。

---

## 1. 现状与问题

**executor → admin 的两个对外信道**：
1. `ExecutorRegistry.doRegister()`：启动注册 + 30s 心跳，向 admin `POST /registry`
2. `CallbackReporter.report()`：每个任务执行完，把结果 `POST /callback` 回调（异步模型，Phase 2）

**共同缺陷（abort-on-first-exception）**：
```java
for (String admin : props.getAdminAddresses().split(",")) {
    restTemplate.postForObject(admin + "/registry", param, Object.class);  // 第一台抛异常就跳出
}
```
- 配了两台（8080/8082），8080 宕机 → 8082 永远收不到注册/回调
- 回调是异步模型的生命线：丢失后只能靠 admin 巡检兜底标 UNKNOWN，任务明明执行成功却被记成未知

**共享 DB 带来的自由度**：多 admin 共用同一 MySQL；`/callback` 按 logId 幂等；注册写共享 `job_registry`。因此「任何一台活着的 admin 都能处理一切」，不依赖特定某台——这让我们可以用最简策略（广播 / 切换）而不失正确性。

---

## 2. 关键决策记录

| # | 决策点 | 结论 |
| --- | --- | --- |
| D1 | 投递策略 | **注册/心跳 = 广播容错**（发给所有 admin，每台独立 try/catch）；**回调 = 故障切换**（只投一台、失败切下一台）。低频控制信道要冗余、高频数据信道要高效（对标 xxl-job 分层思路） |
| D2 | 抽象方式 | 新建 `AdminAddressPool`：executor 侧唯一「与 admin 通信」入口，集中地址解析、RestTemplate、切换游标。优于两组件各自内联（地址解析重复 + 游标状态无处安放） |
| D3 | 切换游标 | 只记「最近一次成功」的 admin 下标；每次从游标开始循环尝试，失败才前移。死机器只在切换瞬间被探一次，不反复打 |
| D4 | 回调重试 | 保留现有退避重试 3 次（0/2/5s）；每次尝试 = 一次完整 failover（全部 admin 试一遍）。全失败打警告，兜底交给 admin 巡检 |
| D5 | 配置 | `adminAddresses` 已是逗号列表，属性不动。samples 默认保留单 8080（一键可用），多 admin 用法写 README |
| D6 | 不做 | admin 侧零改动（端点已幂等）、admin 优先级/权重、回调广播去重、心跳失败额外告警 |

---

## 3. 核心抽象：`AdminAddressPool`（新建）

包 `com.wwjob.executor.admin`。构造时解析地址、空列表 fail-fast；内部共享一个 RestTemplate（3s/10s 超时）+ 失败切换游标。

```java
package com.wwjob.executor.admin;

import com.wwjob.executor.ExecutorProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 与 admin 通信的统一入口：地址列表 + 路由策略 + 故障切换游标。
 * broadcast —— 广播给所有 admin（每台独立容错），用于注册/心跳；
 * failover  —— 从游标起逐个尝试，第一台成功记为游标，用于回调。
 */
public class AdminAddressPool {
    private final List<String> admins;
    private final RestTemplate restTemplate;
    /** 失败切换游标：只记「最近一次成功」的 admin 下标 */
    private final AtomicInteger index = new AtomicInteger(0);

    public AdminAddressPool(ExecutorProperties props) {
        this.admins = parse(props.getAdminAddresses());
        if (admins.isEmpty()) {
            throw new IllegalArgumentException("wwjob.executor.admin-addresses 未配置任何 admin 地址");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
    }

    private List<String> parse(String adminAddresses) {
        List<String> list = new ArrayList<>();
        if (adminAddresses == null) return list;
        for (String s : adminAddresses.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) list.add(t);
        }
        return list;
    }

    /** 广播：发给所有 admin，单台失败不影响其它。返回成功台数（0=全部失败） */
    public int broadcast(String path, Object body) {
        int ok = 0;
        for (String admin : admins) {
            try {
                restTemplate.postForObject(admin + path, body, Object.class);
                ok++;
            } catch (Exception e) {
                System.err.println("broadcast failed: " + admin + path + " -> " + e.getMessage());
            }
        }
        return ok;
    }

    /** 故障切换：从游标起逐个尝试，成功记游标返回 true；全失败返回 false */
    public boolean failover(String path, Object body) {
        int n = admins.size();
        for (int i = 0; i < n; i++) {
            int idx = (index.get() + i) % n;
            try {
                restTemplate.postForObject(admins.get(idx) + path, body, Object.class);
                index.set(idx);
                return true;
            } catch (Exception e) {
                // 这台失败，试下一台
            }
        }
        return false;
    }
}
```

> **为什么广播用「每台独立 try/catch」**：一台宕机只影响它自己，其余照发；返回 0 也只是一次静默失败，30s 后自动重试。
>
> **为什么 failover 不每次从 0 开始**：游标记住了上次成功的 admin，下次直接命中，避免反复穿越死机器。

---

## 4. 两信道改造

### 4.1 `ExecutorRegistry`（注册/心跳）

`doRegister()` 改为广播，删掉自带 RestTemplate，注入 `AdminAddressPool`：

```java
public class ExecutorRegistry {
    private final ExecutorProperties props;
    private final AdminAddressPool adminPool;

    public ExecutorRegistry(ExecutorProperties props, AdminAddressPool adminPool) {
        this.props = props;
        this.adminPool = adminPool;
    }

    @PostConstruct
    public void register() { doRegister(); }

    @Scheduled(fixedRateString = "#{${wwjob.executor.heartbeat-interval-seconds:30} * 1000}")
    public void heartbeat() { doRegister(); }

    private void doRegister() {
        try {
            String ip = props.getAddress();
            if (ip == null || ip.isEmpty()) {
                ip = InetAddress.getLocalHost().getHostAddress();
            }
            String value = ip + ":" + props.getPort();
            RegistryParam param = new RegistryParam();
            param.setRegistryKey(props.getAppName());
            param.setRegistryValue(value);
            adminPool.broadcast("/registry", param);  // 广播到所有 admin，单台失败不影响其它
        } catch (Exception e) {
            System.err.println("register failed: " + e.getMessage());
        }
    }
}
```

### 4.2 `CallbackReporter`（回调）

`report()` 改为 failover 套进退避重试，删掉自带 RestTemplate：

```java
public class CallbackReporter {
    /** 每次尝试前的退避毫秒（0/2/5s，共 3 次尝试） */
    private static final long[] BACKOFF_MS = {0, 2000, 5000};

    private final AdminAddressPool adminPool;

    public CallbackReporter(AdminAddressPool adminPool) {
        this.adminPool = adminPool;
    }

    public void report(CallbackParam param) {
        for (int attempt = 0; attempt < BACKOFF_MS.length; attempt++) {
            if (adminPool.failover("/callback", param)) {
                return;
            }
            if (attempt < BACKOFF_MS.length - 1) {
                try {
                    Thread.sleep(BACKOFF_MS[attempt + 1]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                System.err.println("callback failed after retries, logId=" + param.getLogId()
                        + ": all admins unreachable");
            }
        }
    }
}
```

### 4.3 `ExecutorAutoConfiguration`

新增 pool bean，并注入两组件：

```java
@Bean
public AdminAddressPool adminAddressPool(ExecutorProperties props) {
    return new AdminAddressPool(props);
}

@Bean
public ExecutorRegistry executorRegistry(ExecutorProperties props, AdminAddressPool adminPool) {
    return new ExecutorRegistry(props, adminPool);
}

@Bean
public CallbackReporter callbackReporter(AdminAddressPool adminPool) {
    return new CallbackReporter(adminPool);
}
```

### 4.4 配置与样例

- `ExecutorProperties` 不动（`adminAddresses` 本就是逗号列表）
- samples `application.yml` 默认保留 `admin-addresses: http://localhost:8080`（快速开始一键可用）；多 admin 验证时改为 `http://localhost:8080,http://localhost:8082`
- README：功能特性加「executor 多 admin 故障切换」一条；快速开始补多 admin 配置说明

---

## 5. 正确性论证（为什么共享 DB 下安全）

1. **回调任意台等价**：`/callback` 按 logId 幂等 + job_log 共享表 → 切到 8082 与 8080 等价，无副作用
2. **全挂兜底不变**：3 次重试仍失败 → 日志保持 status=0 → `JobLogTimeoutScanner` 按 timeout 标 UNKNOWN（Phase 2 既有安全网）
3. **注册广播幂等**：两台写同一共享 job_registry 行，无冲突
4. **心跳继续新鲜**：executor 只连 8082 心跳时，共享行 heartbeat_time 照常刷新，两 admin 都判在线
5. **游标线程安全**：`AtomicInteger`，多任务并发回调安全

---

## 6. 边界与已知局限

1. **executor 侧不感知 admin 健康度**：无主动探测，靠实际请求失败驱动切换（够用；30s 心跳周期天然自愈）
2. **回调失败只在「全部 admin 不可达」时丢**：有 3 次重试 + 巡检兜底，整体仍是 at-least-once 语义
3. **不区分 admin 角色**：所有 admin 等价，无主备概念（符合当前集群设计）

---

## 7. 联调与启动（双 admin 验证）

1. admin(8080, local) + admin(8082, local，`$env:SERVER_PORT="8082"`) 共用一库，同多 admin 集群 spec §7
2. executor 改 `admin-addresses: http://localhost:8080,http://localhost:8082`（端口 8081 不变）

---

## 8. 验证方案（端到端，双 admin）

| # | 场景 | 预期 |
| --- | --- | --- |
| 1 | 双 admin + 多地址 executor 启动 | 两 admin 注册表都看到 executor 在线 |
| 2 | cron 任务跑 30s | job_log 每 5s 一条且 status=1（回调正常落库） |
| 3 | 杀 8080 | 新触发任务仍 status=1（回调切到 8082）；8082 心跳继续新鲜 |
| 4 | 重启 8080 | 恢复；无重复回调 |
| 5 | （可选）全杀 admin | 回调 3 次重试后失败；日志 status=0 → 巡检标 UNKNOWN；重启恢复 |

### 实测记录

> 实测日期：2026-08-30（T5 双 admin 端到端）。环境：admin 8080 + admin 8082 共用 MySQL，executor 8081 配置 `admin-addresses: http://localhost:8080,http://localhost:8082`，job 27「回归测试」cron `0/5 * * * * ?` 每 5s 触发。

| # | 场景 | 实测结果 |
| --- | --- | --- |
| 1 | 双 admin + 多地址 executor 启动 | ✅ 两 admin 的 `/registry/list` 均返回同一行 `127.0.0.1:8081`（共享 job_registry 表），heartbeatTime 同步刷新 |
| 2 | cron 任务跑 30s | ✅ executor 启动后 22 条 cron job_log 全部 status=1、handleTime 即时回填（回调正常落库） |
| 3 | 杀 8080 | ✅ 三个信号齐备：(a) executor 日志出现 `broadcast failed: http://localhost:8080/registry -> Connection refused`（证明 8080 在列表、广播容错）；(b) 8082 registry heartbeatTime 持续刷新（心跳直达 8082）；(c) 经 8082 手动触发 job 27 → 新 job_log status=1（回调 failover 到 8082） |
| 4 | 重启 8080 | ✅ 8080 恢复后下一心跳即成功（broadcast failed 计数停止增长），两 admin registry 均新鲜；job_log 无重复回调、id 连续递增 |
| 5 | （可选）全杀 admin | 未测（3 次重试 + 巡检兜底为既有链路，未回归） |

**附带发现（非本次故障切换引入）**：job 27 的 job_log 时间线存在规律性 60s 左右空档（如 00:49:10→00:50:10、00:53:55→00:55:05），且最早一批日志（T4 单 admin 时代，08-29 21:17 起）同样存在——属 `ScheduleHelper` 时间轮调度的既有跳拍现象，与 executor 多 admin 无关；executor 实际收到 dispatch 数与 job_log 条数完全一致（101=101），确认跳拍发生在 admin 调度侧而非执行/回调侧。已记入 multi-admin-cluster spec §9 局限。

---

## 9. 非目标（本次不做）

- admin 地址列表的「优先级/权重/自动发现」
- executor 侧健康探测与告警
- 回调广播（选了切换，天然不重复，省一半调用）
- admin 侧任何改动
