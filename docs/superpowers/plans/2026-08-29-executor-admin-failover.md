# executor 多 admin 故障切换 实现计划

> **For agentic workers:** 本计划**由用户（ice-ww）自研后端代码**，Claude 提供参考实现与指导，不代写。任务按「参考代码 → 编译自测 → 提交」逐个推进；Claude 在每任务后 review 指导，T4/T5 共同验证。

**Goal:** 让 executor 在配了多个 admin 地址时具备故障切换——注册/心跳广播容错（一台 admin 宕机不拖累其它）、结果回调故障切换（只投一台、失败切下一台），单台 admin 宕机不影响任务执行。

**Architecture:** 新建 `AdminAddressPool` 作为 executor 侧唯一「与 admin 通信」入口，集中地址解析、共享 RestTemplate、失败切换游标；`ExecutorRegistry` 走 `broadcast`（注册/心跳）、`CallbackReporter` 走 `failover`（回调）。共享 DB + 幂等 callback 保证切到任何一台 admin 都等价。

**Tech Stack:** Java 17 + Spring Boot 3.3 + RestTemplate（3s/10s 超时）。

**Spec:** `docs/superpowers/specs/2026-08-29-executor-admin-failover-design.md`

## Global Constraints

- **后端代码用户自研**，Claude 只给参考实现与指导（沿用 [[user-participation-preference]] 约定）
- 组件风格照现有 executor 代码：`System.err.println` 记日志、RestTemplate 3s 连接 / 10s 读超时、构造器注入
- **不引入新依赖**（不做 slf4j 改造、不加 actuator）
- `ExecutorProperties` 属性不动（`adminAddresses` 本就是逗号列表）
- samples `application.yml` 默认保持单 `http://localhost:8080`（一键可用）；多 admin 用法写 README
- **真实 MySQL 密码 / SMTP 授权码在 gitignored `application-local.yml`**，代码 / commit message 不得出现
- admin 以 local profile 启动；端口：admin 8080 / 8082，executor 8081

---

### Task 1: 新建 AdminAddressPool + 注册 bean

**Files:**
- Create: `D:\javacode\ww-job\ww-job-executor\src\main\java\com\wwjob\executor\admin\AdminAddressPool.java`
- Modify: `D:\javacode\ww-job\ww-job-executor\src\main\java\com\wwjob\executor\auto\ExecutorAutoConfiguration.java`（加 import + 新增 bean）

**Consumes:** `ExecutorProperties.adminAddresses`.
**Produces:** `int broadcast(String path, Object body)`、`boolean failover(String path, Object body)` —— T2/T3 依赖。

- [ ] **Step 1: 新建 AdminAddressPool.java**

保留类头 `@author 王威 @version 1.0` 注释（照现有组件习惯），正文参考：

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

- [ ] **Step 2: ExecutorAutoConfiguration 加 import + 注册 bean**

文件顶部加 `import com.wwjob.executor.admin.AdminAddressPool;`，类内新增：

```java
    @Bean
    public AdminAddressPool adminAddressPool(ExecutorProperties props) {
        return new AdminAddressPool(props);
    }
```

- [ ] **Step 3: 编译自测**

```bash
mvn -pl ww-job-executor -am compile -q
```
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add ww-job-executor/src/main/java/com/wwjob/executor/admin/AdminAddressPool.java ww-job-executor/src/main/java/com/wwjob/executor/auto/ExecutorAutoConfiguration.java
git commit -m "feat: AdminAddressPool（广播容错 + 回调故障切换）"
```

---

### Task 2: ExecutorRegistry 改走广播

**Files:**
- Modify: `D:\javacode\ww-job\ww-job-executor\src\main\java\com\wwjob\executor\registry\ExecutorRegistry.java`
- Modify: `D:\javacode\ww-job\ww-job-executor\src\main\java\com\wwjob\executor\auto\ExecutorAutoConfiguration.java`（`executorRegistry` bean 方法签名）

**Consumes:** T1 `AdminAddressPool`.
**Produces:** 注册/心跳广播到所有 admin，单台失败不影响其它。

- [ ] **Step 1: 全文改造 ExecutorRegistry.java**

删掉自带 RestTemplate 与 `SimpleClientHttpRequestFactory` import，注入 `AdminAddressPool`：

```java
package com.wwjob.executor.registry;

import com.wwjob.core.model.RegistryParam;
import com.wwjob.executor.ExecutorProperties;
import com.wwjob.executor.admin.AdminAddressPool;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;

import java.net.InetAddress;

/**
 * 执行器注册器：启动时注册 + 定时心跳，向所有 admin 的 /registry 广播（单台失败不影响其它）。
 */
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

- [ ] **Step 2: AutoConfiguration 的 executorRegistry bean 加 adminPool 参数**

```java
    @Bean
    public ExecutorRegistry executorRegistry(ExecutorProperties props, AdminAddressPool adminPool) {
        return new ExecutorRegistry(props, adminPool);
    }
```

- [ ] **Step 3: 编译自测**（`mvn -pl ww-job-executor -am compile -q` → SUCCESS）

- [ ] **Step 4: Commit**

```bash
git add ww-job-executor/src/main/java/com/wwjob/executor/registry/ExecutorRegistry.java ww-job-executor/src/main/java/com/wwjob/executor/auto/ExecutorAutoConfiguration.java
git commit -m "feat: ExecutorRegistry 注册/心跳改走 AdminAddressPool 广播容错"
```

---

### Task 3: CallbackReporter 改走 failover

**Files:**
- Modify: `D:\javacode\ww-job\ww-job-executor\src\main\java\com\wwjob\executor\callback\CallbackReporter.java`
- Modify: `D:\javacode\ww-job\ww-job-executor\src\main\java\com\wwjob\executor\auto\ExecutorAutoConfiguration.java`（`callbackReporter` bean 方法签名）

**Consumes:** T1 `AdminAddressPool`.
**Produces:** 回调只投一台活着的 admin，失败切下一台；保留 0/2/5s 退避重试 3 次。

- [ ] **Step 1: 全文改造 CallbackReporter.java**

删掉自带 RestTemplate 与 `ExecutorProperties`，注入 `AdminAddressPool`：

```java
package com.wwjob.executor.callback;

import com.wwjob.core.model.CallbackParam;
import com.wwjob.executor.admin.AdminAddressPool;

/**
 * 结果上报器：把执行结果回调给 admin 的 /callback。
 * 故障切换：只投一台活着的 admin，失败自动切下一台；全部失败则退避重试 3 次（0/2/5s），
 * 全失败打警告——真实结果只能靠 admin 巡检兜底标未知。
 */
public class CallbackReporter {
    /** 每次尝试前的退避毫秒（0/2/5s，共 3 次尝试，最后一次尝试后全失败则放弃） */
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

- [ ] **Step 2: AutoConfiguration 的 callbackReporter bean 加 adminPool 参数**

```java
    @Bean
    public CallbackReporter callbackReporter(AdminAddressPool adminPool) {
        return new CallbackReporter(adminPool);
    }
```

- [ ] **Step 3: 编译自测**（`mvn -pl ww-job-executor -am compile -q` → SUCCESS）

- [ ] **Step 4: Commit**

```bash
git add ww-job-executor/src/main/java/com/wwjob/executor/callback/CallbackReporter.java ww-job-executor/src/main/java/com/wwjob/executor/auto/ExecutorAutoConfiguration.java
git commit -m "feat: CallbackReporter 结果回调改走故障切换（失败切下一台 admin）"
```

---

### Task 4: 全量编译 + 单 admin 回归

**Files:** 无代码改动（验证）。可先 `git status` 确认工作区干净再开始。

- [ ] **Step 1: 全量编译**

```bash
mvn clean compile -q
```
Expected: BUILD SUCCESS（四个模块）。

- [ ] **Step 2: 起单 admin + executor**

两窗口（executor samples 保持默认单 8080）：
```bash
mvn -pl ww-job-admin -am spring-boot:run -Dspring-boot.run.profiles=local
mvn -pl ww-job-executor-samples -am spring-boot:run
```

- [ ] **Step 3: 注册/心跳回归**

```bash
curl http://localhost:8080/registry/list
```
Expected: 能看到 sample-executor 在线（`127.0.0.1:8081`）——广播退化为单台，行为与改造前一致。

- [ ] **Step 4: cron 回调回归**（异步模型链路未破坏）

建任务并等 ~35s：
```bash
curl -X POST http://localhost:8080/job -H "Content-Type: application/json" \
  -d '{"jobGroupId":1,"handlerName":"demoHandler","cron":"0/5 * * * * ?","routeStrategy":"round","retryCount":0,"blockStrategy":"SINGLE","triggerStatus":1}'
```
Expected: `GET /joblog/page?jobId=<新建id>&size=50` 每 5s 一条、全 status=1（说明回调正常落到 admin）、无 status=0 积压。

- [ ] **Step 5: 手动触发回归**

```bash
curl -X POST http://localhost:8080/job/<id>/trigger
```
Expected: 新日志 triggerType=manual，短时间后 status=1。

- [ ] **Step 6: Commit**（T4 无代码变更，跳过；若期间修 bug，单独提交）

---

### Task 5: 双 admin 端到端验证 + README + 推送

**Files:**
- Modify: `D:\javacode\ww-job\README.md`
- Modify: `D:\javacode\ww-job\ww-job-executor-samples\src\main\resources\application.yml`（临时改，测完决定保留/回退）

- [ ] **Step 1: 起第二台 admin（8082，同库）+ executor 配双地址**

保持 8080 admin + executor 运行，新窗口起 8082：
```bash
$env:SERVER_PORT = "8082"; $env:SPRING_PROFILES_ACTIVE = "local"; mvn -pl ww-job-admin spring-boot:run
```
把 samples `application.yml` 的 `admin-addresses` 改为：
```yaml
    admin-addresses: http://localhost:8080,http://localhost:8082
```
重启 executor（让配置生效）。

> 先确认 8082 起来了再动 executor，避免连不上。

- [ ] **Step 2: 双 admin 注册表都看到 executor**

```bash
curl http://localhost:8080/registry/list
curl http://localhost:8082/registry/list
```
Expected: 两台都返回 sample-executor `127.0.0.1:8081`——广播容错生效（executor 与整个集群建联）。

- [ ] **Step 3: cron 回调正常**

观察 ~35s：
```bash
curl "http://localhost:8080/joblog/page?jobId=<cron任务id>&size=50"
```
Expected: 每 5s 一条、全 status=1、无积压——回调在双 admin 下照常落库。

- [ ] **Step 4: 杀 8080，回调切到 8082**

Ctrl+C 停掉 8080 admin。等 ~10s，再触发任务（cron 继续或手动触发）：
```bash
curl -X POST http://localhost:8082/job/<id>/trigger
```
Expected: 新日志**短时间后 status=1**——8080 死了，回调故障切换到了 8082；同时 `curl http://localhost:8082/registry/list` 仍看到 executor 在线（心跳广播容错，8082 正常收到）。

> 判定依据：job_log 共享表，任何一台写都一样；新触发能变 status=1 就证明「回调到达了活着的 admin」。

- [ ] **Step 5: 重启 8080，恢复 + 无重复回调**

再起 8080，观察 ~20s：
- `curl http://localhost:8080/registry/list` → executor 在线恢复
- job_log 新记录 status=1 且**无重复**（failover 每任务只成功投递一次）

- [ ] **Step 6: README 更新**

1. 功能特性区加一条（与「多 admin 集群」并列）：
   `- **executor 多 admin 故障切换**：注册/心跳广播容错 + 结果回调故障切换，单台 admin 宕机不影响任务执行`
2. 快速开始「多 admin 集群」blockquote 补充 executor 侧：
   `> executor 配多个 admin 地址（admin-addresses 逗号分隔）即可接入集群；回调自动切换，单台宕机不影响执行。`
3. samples `application.yml` 决定：改回单 8080（默认一键可用）或保留双地址（广播容错，单 admin 下 8082 报错静默无害）均可——推荐改回单 8080。

- [ ] **Step 7: 提交**

```bash
git add README.md ww-job-executor-samples/src/main/resources/application.yml
git commit -m "docs: executor 多 admin 故障切换已实现，README 更新"
```

- [ ] **Step 8: 推送**

```bash
git push origin main
```
（代理开着；遇网络问题参照以往：检查 7897 代理后重试。）

---

### Task 6: spec 实测记录回填 + 推送

**Files:**
- Modify: `D:\javacode\ww-job\docs\superpowers\specs\2026-08-29-executor-admin-failover-design.md`（§8 实测记录）
- Modify: `D:\javacode\ww-job\docs\superpowers\specs\2026-08-29-multi-admin-cluster-design.md`（§9 局限 1 标记已解决）

- [ ] **Step 1: 回填 executor-admin-failover spec §8**

把 T4/T5 实测结果（单 admin 回归、双注册表、杀 8080 回调切 8082、重启恢复、无重复）填进「实测记录」。

- [ ] **Step 2: 更新 multi-admin spec §9 局限 1**

原文「executor 仍连单 admin：……不自动切换（xxl-job 的 admin 地址列表 + 失败切换为后续项）」改为：
`1. **executor 多 admin 故障切换（已解决）**：executor 配多个 admin 地址即可接入集群，注册/心跳广播容错、回调故障切换，单台 admin 宕机不影响执行（见 `2026-08-29-executor-admin-failover-design.md`）。`

- [ ] **Step 3: 提交 + 推送**

```bash
git add docs/superpowers/specs/2026-08-29-executor-admin-failover-design.md docs/superpowers/specs/2026-08-29-multi-admin-cluster-design.md
git commit -m "docs: executor 多 admin 故障切换端到端验证记录回填"
git push origin main
```
