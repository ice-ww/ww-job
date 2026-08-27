# ww-job Phase 2 · 异步回调模型 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把触发链路从「同步请求-响应」改成「异步回调」——admin 只投递+收账，执行器异步执行并回调结果，根治「>10s 任务重叠挡不住 / 重试重复执行 / admin 线程被占用」三个边界。

**Architecture:** 协议新增 `CallbackParam`（logId 绑结果）；执行器 `/run` 改「立即受理 + 线程池异步执行 + 完成后 POST /callback」；admin 新增 `/callback` 收账端点、`trigger()` 重构为「事务决策（行锁 + DB status=0 计数互斥）+ 锁外投递」、新增超时巡检兜底。

**Tech Stack:** Java 17、Spring Boot 3.3、Maven、MySQL 8、MyBatis-Plus、HTTP。

**Spec:** `docs/superpowers/specs/2026-08-26-async-callback-design.md`

## Global Constraints

- Java 17；Spring Boot 3.3.x；Maven 构建；代码风格延续现有（`@author 王威`、`@version 1.0`）
- 包根：`com.wwjob.core` / `com.wwjob.admin` / `com.wwjob.executor`
- 互斥位 = `job_log` 中 `status=0`（RUNNING）的日志；`status` 常量用 `JobLog.STATUS_*`（0/1/2/3）
- `retryCount` 只作用于投递阶段；`ack` 收到后绝不因执行结果重试
- 事务用 `TransactionTemplate`（不写私有方法上的 `@Transactional`——Spring 代理不拦截私有方法）
- `job_info.timeout`：0 或 null 时巡检默认 60s；>0 则按配置
- 执行器线程池：有界队列 + `AbortPolicy`，满则 `/run` 快速失败
- 回调失败退避重试 3 次（0/2/5s），全失败打 `System.err` 警告
- admin 触发 `RestTemplate`：connect 3s / read **5s**（只等 ack）；执行器心跳/上报 `RestTemplate`：connect 3s / read 10s
- schema 不加列
- 分支：本次在 `feat/phase2-async-callback` 上开发，全部完成合并回 `main`（练习分支/合并）
- 每个 Task 末尾 `git commit`，信息用 `类型: 说明`（feat/fix/docs/test）
- 端到端验证沿用：`mvn -q -DskipTests install` → 后台起 admin（local profile）→ 后台起 samples → curl / SQL 断言。Windows grep 中文日志会 GBK 乱码，用 ASCII 子串（`logId=`、`slow`、`Started`）

---

### Task 1: core `CallbackParam` DTO（TDD）

**Files:**
- Create: `ww-job-core/src/main/java/com/wwjob/core/model/CallbackParam.java`
- Test: `ww-job-core/src/test/java/com/wwjob/core/model/CallbackParamTest.java`

**Interfaces:**
- Produces: `com.wwjob.core.model.CallbackParam`，构造 `CallbackParam(long logId, int handleCode, String handleMsg, long handleTime)`，getter/setter/`toString`。后续 Task 2/3 依赖它作为 `/callback` 的请求体。

- [ ] **Step 1: 写失败测试**

```java
package com.wwjob.core.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author 王威
 * @version 1.0
 */
class CallbackParamTest {

    @Test
    void constructsAndExposesFields() {
        CallbackParam p = new CallbackParam(42L, ReturnT.SUCCESS_CODE, "ok", 1700000000000L);
        assertEquals(42L, p.getLogId());
        assertEquals(ReturnT.SUCCESS_CODE, p.getHandleCode());
        assertEquals("ok", p.getHandleMsg());
        assertEquals(1700000000000L, p.getHandleTime());
    }

    @Test
    void toStringContainsKeyFields() {
        CallbackParam p = new CallbackParam(1L, 500, "boom", 0L);
        assertTrue(p.toString().contains("logId = 1"));
        assertTrue(p.toString().contains("handleCode = 500"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl ww-job-core test -Dtest=CallbackParamTest`
Expected: 编译失败，`CallbackParam` 不存在。

- [ ] **Step 3: 写实现**

```java
package com.wwjob.core.model;

/**
 * @author 王威
 * @version 1.0
 */

/**
 * 执行器回调 admin 的执行结果：logId 是对账锚点，handleCode 沿用 ReturnT 语义（200 成功 / 500 失败）。
 */
public class CallbackParam {
    private long logId;
    private int handleCode;
    private String handleMsg;
    private long handleTime;

    public CallbackParam() {
    }

    public CallbackParam(long logId, int handleCode, String handleMsg, long handleTime) {
        this.logId = logId;
        this.handleCode = handleCode;
        this.handleMsg = handleMsg;
        this.handleTime = handleTime;
    }

    public long getLogId() { return logId; }
    public void setLogId(long logId) { this.logId = logId; }
    public int getHandleCode() { return handleCode; }
    public void setHandleCode(int handleCode) { this.handleCode = handleCode; }
    public String getHandleMsg() { return handleMsg; }
    public void setHandleMsg(String handleMsg) { this.handleMsg = handleMsg; }
    public long getHandleTime() { return handleTime; }
    public void setHandleTime(long handleTime) { this.handleTime = handleTime; }

    @Override
    public String toString() {
        return "CallbackParam{logId = " + logId + ", handleCode = " + handleCode
                + ", handleMsg = " + handleMsg + ", handleTime = " + handleTime + "}";
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl ww-job-core test -Dtest=CallbackParamTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add ww-job-core/src/main/java/com/wwjob/core/model/CallbackParam.java ww-job-core/src/test/java/com/wwjob/core/model/CallbackParamTest.java
git commit -m "feat: 新增 CallbackParam 回调结果 DTO（logId 对账）"
```

---

### Task 2: admin `POST /callback` 收账端点

**Files:**
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/controller/CallbackController.java`

**Interfaces:**
- Consumes: `CallbackParam`（Task 1）、`JobLogMapper`、`JobLog.STATUS_SUCCESS/FAIL`、`ReturnT.SUCCESS_CODE/FAIL_CODE`
- Produces: `POST /callback`（body=CallbackParam）→ `ReturnT<String>`。Task 3 的执行器上报器依赖这个端点。

- [ ] **Step 1: 写控制器**

```java
package com.wwjob.admin.controller;

import com.wwjob.admin.entity.JobLog;
import com.wwjob.admin.mapper.JobLogMapper;
import com.wwjob.core.model.CallbackParam;
import com.wwjob.core.model.ReturnT;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * @author 王威
 * @version 1.0
 */

/**
 * 执行器回调收账端点：按 logId 把日志更新为真实结果。
 * 最终一致：即使日志已被巡检标记为 status=3，回调到达也覆盖为真实结果；重复回调按 logId 幂等覆盖。
 */
@RestController
public class CallbackController {
    private final JobLogMapper jobLogMapper;

    public CallbackController(JobLogMapper jobLogMapper) {
        this.jobLogMapper = jobLogMapper;
    }

    @PostMapping("/callback")
    public ReturnT<String> callback(@RequestBody CallbackParam param) {
        JobLog log = jobLogMapper.selectById(param.getLogId());
        if (log == null) {
            return ReturnT.fail("logId 不存在: " + param.getLogId());
        }
        log.setStatus(param.getHandleCode() == ReturnT.SUCCESS_CODE
                ? JobLog.STATUS_SUCCESS : JobLog.STATUS_FAIL);
        log.setHandleCode(param.getHandleCode());
        log.setHandleMsg(param.getHandleMsg());
        log.setHandleTime(LocalDateTime.ofInstant(
                Instant.ofEpochMilli(param.getHandleTime()), ZoneId.systemDefault()));
        jobLogMapper.updateById(log);  // MP 默认忽略 null 字段 → 只更新上述字段
        return ReturnT.success();
    }
}
```

- [ ] **Step 2: 编译**

Run: `mvn -q -DskipTests install`
Expected: BUILD SUCCESS。

- [ ] **Step 3: 手工验证（SQL 造一条 status=0 日志，curl 回调它）**

```bash
# 起 admin（后台），等 "Started WwJobAdminApplication"
mvn -pl ww-job-admin spring-boot:run -Dspring-boot.run.profiles=local > /tmp/ww-admin.log 2>&1 &

# 造一条 status=0 的日志（jobId 随便用 1，不需要任务真的存在）
mysql -uroot -p'PASSWORD_REMOVED' ww_job -e \
"INSERT INTO job_log(job_id,job_group_id,handler_name,trigger_type,trigger_time,status) VALUES (1,1,'demoHandler','manual',NOW(),0); SELECT LAST_INSERT_ID() AS logId;"

# 用上一步返回的 logId 回调（示例 12345）
curl -s -X POST http://localhost:8080/callback \
  -H "Content-Type: application/json" \
  -d '{"logId":12345,"handleCode":200,"handleMsg":"手动验证成功","handleTime":'$(date +%s%3N)'}'

# 断言：status 变 1，handle_msg/handle_time 被填
mysql -uroot -p'PASSWORD_REMOVED' ww_job -e "SELECT id,status,handle_code,handle_msg,handle_time FROM job_log WHERE id=12345;"
```

Expected: `status=1`、`handle_code=200`、`handle_msg='手动验证成功'`、`handle_time` 非空。

- [ ] **Step 4: 清掉验证数据**

```bash
mysql -uroot -p'PASSWORD_REMOVED' ww_job -e "DELETE FROM job_log WHERE id=12345;"
```

- [ ] **Step 5: Commit**

```bash
git add ww-job-admin/src/main/java/com/wwjob/admin/controller/CallbackController.java
git commit -m "feat: admin 新增 /callback 回调收账端点（最终一致更新日志）"
```

---

### Task 3: executor 异步化（线程池 + JobRunner + CallbackReporter + /run 快速受理）

**Files:**
- Modify: `ww-job-executor/src/main/java/com/wwjob/executor/auto/ExecutorAutoConfiguration.java`
- Modify: `ww-job-executor/src/main/java/com/wwjob/executor/controller/JobController.java`
- Create: `ww-job-executor/src/main/java/com/wwjob/executor/callback/JobRunner.java`
- Create: `ww-job-executor/src/main/java/com/wwjob/executor/callback/CallbackReporter.java`

**Interfaces:**
- Consumes: `CallbackParam`（Task 1）、`ExecutorProperties.getAdminAddresses()`、`JobHandlerRegistry`、`TriggerParam`、`JobContext`
- Produces: bean `ExecutorService jobExecutor`（`ww-job-executor-runner` 线程）、bean `CallbackReporter`；`/run` 语义变更：handler 存在即立即返回 ack；线程池满返回「执行器繁忙」。Task 4 的 admin 投递依赖这个新语义。

- [ ] **Step 1: 改 `ExecutorAutoConfiguration` 增加两个 bean**

```java
package com.wwjob.executor.auto;

import com.wwjob.executor.ExecutorProperties;
import com.wwjob.executor.callback.CallbackReporter;
import com.wwjob.executor.registry.ExecutorRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 执行器自动配置：Spring Boot 启动时通过 imports 文件加载。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ExecutorProperties.class)
@ConditionalOnProperty(prefix = "wwjob.executor", name = "app-name")
public class ExecutorAutoConfiguration {

    @Bean
    public ExecutorRegistry executorRegistry(ExecutorProperties props) {
        return new ExecutorRegistry(props);
    }

    /** 任务执行线程池：有界队列 + AbortPolicy，满则拒绝快速失败（/run 返回"执行器繁忙"） */
    @Bean
    public ExecutorService jobExecutor() {
        int cores = Runtime.getRuntime().availableProcessors();
        return new ThreadPoolExecutor(cores, cores * 2, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(100),
                r -> { Thread t = new Thread(r, "ww-job-executor-runner"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    public CallbackReporter callbackReporter(ExecutorProperties props) {
        return new CallbackReporter(props);
    }
}
```

- [ ] **Step 2: 新建 `CallbackReporter`**

```java
package com.wwjob.executor.callback;

import com.wwjob.core.model.CallbackParam;
import com.wwjob.executor.ExecutorProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * @author 王威
 * @version 1.0
 */

/**
 * 结果上报器：把执行结果回调给 admin 的 /callback。
 * 失败退避重试 3 次（0/2/5s），全失败打警告——真实结果只能靠 admin 巡检兜底标未知。
 */
public class CallbackReporter {
    /** 每次尝试前的退避毫秒（0/2/5s，共 3 次尝试，最后一次尝试后全失败则放弃） */
    private static final long[] BACKOFF_MS = {0, 2000, 5000};

    private final ExecutorProperties props;
    private final RestTemplate restTemplate;

    public CallbackReporter(ExecutorProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
    }

    public void report(CallbackParam param) {
        for (int attempt = 0; attempt < BACKOFF_MS.length; attempt++) {
            try {
                for (String admin : props.getAdminAddresses().split(",")) {
                    restTemplate.postForObject(admin + "/callback", param, Object.class);
                }
                return;
            } catch (Exception e) {
                if (attempt < BACKOFF_MS.length - 1) {
                    try {
                        Thread.sleep(BACKOFF_MS[attempt + 1]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                } else {
                    System.err.println("callback failed after retries, logId=" + param.getLogId()
                            + ": " + e.getMessage());
                }
            }
        }
    }
}
```

- [ ] **Step 3: 新建 `JobRunner`（异步执行单元）**

```java
package com.wwjob.executor.callback;

import com.wwjob.core.context.JobContext;
import com.wwjob.core.handler.IJobHandler;
import com.wwjob.core.model.ReturnT;
import com.wwjob.core.model.TriggerParam;

/**
 * @author 王威
 * @version 1.0
 */

/**
 * 异步执行单元：跑 handler，无论成败都构造 CallbackParam 回调给 admin。
 */
public class JobRunner implements Runnable {
    private final IJobHandler handler;
    private final TriggerParam param;
    private final CallbackReporter reporter;

    public JobRunner(IJobHandler handler, TriggerParam param, CallbackReporter reporter) {
        this.handler = handler;
        this.param = param;
        this.reporter = reporter;
    }

    @Override
    public void run() {
        JobContext ctx = new JobContext();
        ctx.setJobId(param.getJobId());
        ctx.setLogId(param.getLogId());
        ctx.setExecutorParam(param.getExecutorParam());
        ctx.setShardIndex(param.getShardIndex());
        ctx.setShardTotal(param.getShardTotal());
        ReturnT<String> result;
        try {
            result = handler.execute(ctx);
        } catch (Exception e) {
            result = ReturnT.fail(e.getMessage());
        }
        reporter.report(new CallbackParam(param.getLogId(),
                result.getCode(), result.getMsg(), System.currentTimeMillis()));
    }
}
```

- [ ] **Step 4: 改 `JobController.run()` 为异步受理**

```java
package com.wwjob.executor.controller;

import com.wwjob.core.handler.IJobHandler;
import com.wwjob.core.model.ReturnT;
import com.wwjob.core.model.TriggerParam;
import com.wwjob.executor.callback.CallbackReporter;
import com.wwjob.executor.callback.JobRunner;
import com.wwjob.executor.handler.JobHandlerRegistry;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * @author 王威
 * @version 1.0
 */
@RestController
public class JobController {
    private final JobHandlerRegistry registry;
    private final ExecutorService jobExecutor;
    private final CallbackReporter callbackReporter;

    public JobController(JobHandlerRegistry registry, ExecutorService jobExecutor,
                         CallbackReporter callbackReporter) {
        this.registry = registry;
        this.jobExecutor = jobExecutor;
        this.callbackReporter = callbackReporter;
    }

    @PostMapping("/run")
    public ReturnT<String> run(@RequestBody TriggerParam param) {
        IJobHandler handler = registry.get(param.getHandler());
        if (handler == null) {
            return ReturnT.fail("handler 未注册: " + param.getHandler());
        }
        try {
            jobExecutor.execute(new JobRunner(handler, param, callbackReporter));
        } catch (RejectedExecutionException e) {
            // 线程池满：拒绝快速失败，admin 视为明确失败可换机重投
            return ReturnT.fail("执行器繁忙，请稍后");
        }
        return ReturnT.success("已受理, logId=" + param.getLogId());
    }
}
```

- [ ] **Step 5: 编译**

Run: `mvn -q -DskipTests install`
Expected: BUILD SUCCESS。

- [ ] **Step 6: 手工验证「/run 立即受理 + 异步回调落库」**

```bash
# 起 admin（若没在跑）+ samples（后台），等两个 Started
mvn -pl ww-job-executor-samples spring-boot:run > /tmp/ww-samples.log 2>&1 &

# 造一条 status=0 日志，拿 logId（示例 12346）
mysql -uroot -p'PASSWORD_REMOVED' ww_job -e \
"INSERT INTO job_log(job_id,job_group_id,handler_name,trigger_type,trigger_time,status) VALUES (1,1,'slowHandler','manual',NOW(),0); SELECT LAST_INSERT_ID();"

# 用 `time` 验证 /run 对 slowHandler（睡 15s）立即返回（应在 1s 内，而非 15s）
time curl -s -X POST http://localhost:8081/run \
  -H "Content-Type: application/json" \
  -d '{"jobId":1,"handler":"slowHandler","logId":12346}'
# Expected: 立即返回 {"code":200,..."已受理, logId=12346"}，time 显示 <1s

# 等 ~16s，断言日志被回调为 status=1（异步完成，真实结果）
sleep 16
mysql -uroot -p'PASSWORD_REMOVED' ww_job -e "SELECT id,status,handle_code,handle_msg,handle_time FROM job_log WHERE id=12346;"
```

Expected: `status=1`、`handle_code=200`、`handle_msg` 含「slow 执行成功」、`handle_time` 非空。

- [ ] **Step 7: 清掉验证数据**

```bash
mysql -uroot -p'PASSWORD_REMOVED' ww_job -e "DELETE FROM job_log WHERE id=12346;"
```

- [ ] **Step 8: Commit**

```bash
git add ww-job-executor/src/main/java/com/wwjob/executor/auto/ExecutorAutoConfiguration.java ww-job-executor/src/main/java/com/wwjob/executor/controller/JobController.java ww-job-executor/src/main/java/com/wwjob/executor/callback/JobRunner.java ww-job-executor/src/main/java/com/wwjob/executor/callback/CallbackReporter.java
git commit -m "feat: executor /run 改异步受理 + 执行结果回调 admin"
```

---

### Task 4: admin `trigger()` 重构（事务决策 + DB 计数互斥 + 锁外投递）

**Files:**
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/service/JobTriggerServiceImpl.java`
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/mapper/JobInfoMapper.java`

**Interfaces:**
- Consumes: `ExecutorRouterService.route(...)`、`JobLog.STATUS_*`、`ReturnT`、`TriggerParam`、`PlatformTransactionManager`、`TransactionTemplate`
- Produces: `JobInfoMapper.selectByIdForUpdate(long id)`（行锁查询）；`trigger(jobId, triggerType)` 新语义：决策（行锁 + `count(status=0)`）→ 锁外投递 ack → 等回调。Task 5 的巡检依赖 status=0 日志作为「在跑」标记。

- [ ] **Step 1: `JobInfoMapper` 加行锁查询**

```java
package com.wwjob.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wwjob.admin.entity.JobInfo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * @author 王威
 * @version 1.0
 */
public interface JobInfoMapper extends BaseMapper<JobInfo> {

    /** 行锁：串行化同一任务的并发触发决策（不同任务的行互不阻塞）。必须在事务内使用。 */
    @Select("SELECT * FROM job_info WHERE id = #{id} FOR UPDATE")
    JobInfo selectByIdForUpdate(@Param("id") long id);
}
```

- [ ] **Step 2: 重写 `JobTriggerServiceImpl`**

```java
package com.wwjob.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wwjob.admin.entity.JobInfo;
import com.wwjob.admin.entity.JobLog;
import com.wwjob.admin.mapper.JobInfoMapper;
import com.wwjob.admin.mapper.JobLogMapper;
import com.wwjob.core.model.ReturnT;
import com.wwjob.core.model.TriggerParam;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.time.LocalDateTime;

/**
 * @author 王威
 * @version 1.0
 */
@Service
public class JobTriggerServiceImpl implements JobTriggerService {
    private final JobInfoMapper jobInfoMapper;
    private final JobLogMapper jobLogMapper;
    private final ExecutorRouterService routerService;
    private final RestTemplate restTemplate;
    private final TransactionTemplate transactionTemplate;

    public JobTriggerServiceImpl(JobInfoMapper jobInfoMapper, JobLogMapper jobLogMapper,
                                 ExecutorRouterService routerService,
                                 PlatformTransactionManager transactionManager) {
        this.jobInfoMapper = jobInfoMapper;
        this.jobLogMapper = jobLogMapper;
        this.routerService = routerService;
        // /run 只等 ack（瞬时）：connect 3s / read 5s
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void trigger(long jobId, String triggerType) {
        JobInfo job = jobInfoMapper.selectById(jobId);
        if (job == null) return;

        boolean single = "SINGLE".equalsIgnoreCase(job.getBlockStrategy());
        // 决策：行锁 + DB status=0 计数判断互斥。锁内不发 HTTP。
        JobLog log = transactionTemplate.execute(status -> decide(job, single, triggerType));
        if (log == null) return;  // 被阻塞，已插 status=3 日志

        // 锁外投递：retryCount 只作用于投递阶段
        dispatch(log, job);
    }

    /** 事务决策。返回 null 表示被阻塞；否则返回新插入的 status=0 日志（= 互斥位） */
    private JobLog decide(JobInfo job, boolean single, String triggerType) {
        jobInfoMapper.selectByIdForUpdate(job.getId());  // 行锁串行化同一任务
        long running = jobLogMapper.selectCount(new QueryWrapper<JobLog>()
                .eq("job_id", job.getId())
                .eq("status", JobLog.STATUS_RUNNING));
        if (single && running > 0) {
            saveLog(job, triggerType, "任务上一次执行尚未结束，本次触发被阻塞丢弃", null, JobLog.STATUS_UNKNOWN);
            return null;
        }
        return saveLog(job, triggerType, null, null, JobLog.STATUS_RUNNING);
    }

    /** 投递 /run 等 ack。ack 成功即完成投递，执行结果等执行器回调 */
    private void dispatch(JobLog log, JobInfo job) {
        int retryCount = job.getRetryCount() == null ? 0 : job.getRetryCount();
        ReturnT<?> lastResult = null;
        Exception lastError = null;
        for (int attempt = 0; attempt <= retryCount; attempt++) {
            String address = routerService.route(job.getJobGroupId(), job.getRouteStrategy(), job.getId());
            if (address == null) {
                markFailed(log, "无可用执行器");
                return;
            }
            log.setExecutorAddress(address);
            TriggerParam param = new TriggerParam();
            param.setJobId(job.getId());
            param.setHandler(job.getHandlerName());
            param.setExecutorParam(job.getExecutorParam());
            param.setLogId(log.getId());
            try {
                lastResult = restTemplate.postForObject("http://" + address + "/run", param, ReturnT.class);
                if (lastResult != null && lastResult.getCode() == ReturnT.SUCCESS_CODE) {
                    // ack 收到：投递成功，记录执行地址，结果等回调
                    log.setHandleMsg("已投递，等待执行器回调");
                    jobLogMapper.updateById(log);
                    job.setTriggerLastTime(System.currentTimeMillis());
                    jobInfoMapper.updateById(job);
                    return;
                }
                lastError = null;  // 明确失败 ack（执行器繁忙等）→ 可重试投递
            } catch (Exception e) {
                lastError = e;
                if (isTimeout(e)) {
                    // ack 读超时：执行器可能已受理但回执丢失，重试=重复执行 → 放弃，等回调/巡检兜底
                    log.setHandleMsg("已投递但未收到受理回执，结果等待执行器回调");
                    jobLogMapper.updateById(log);
                    return;
                }
                // 连接被拒等 → 可重试投递
            }
        }
        markFailed(log, lastError != null ? lastError.getMessage()
                : (lastResult == null ? "投递失败" : lastResult.getMsg()));
    }

    private void markFailed(JobLog log, String msg) {
        log.setStatus(JobLog.STATUS_FAIL);
        log.setHandleCode(ReturnT.FAIL_CODE);
        log.setHandleMsg(msg);
        log.setHandleTime(LocalDateTime.now());
        jobLogMapper.updateById(log);
    }

    /** RestTemplate 把 SocketTimeoutException 包在 ResourceAccessException 里，沿 cause 链查找 */
    private boolean isTimeout(Exception e) {
        if (e == null) return false;
        Throwable c = e;
        while (c != null) {
            if (c instanceof SocketTimeoutException) return true;
            c = c.getCause();
        }
        return false;
    }

    private JobLog saveLog(JobInfo job, String triggerType, String failMsg, String address, int status) {
        JobLog log = new JobLog();
        log.setJobId(job.getId());
        log.setJobGroupId(job.getJobGroupId());
        log.setExecutorAddress(address);
        log.setHandlerName(job.getHandlerName());
        log.setTriggerType(triggerType);
        log.setTriggerTime(LocalDateTime.now());
        log.setStatus(status);
        log.setHandleMsg(failMsg);
        jobLogMapper.insert(log);
        return log;
    }
}
```

> 注意：互斥位不再用内存 `runningJobIds`，而是「status=0 日志的计数」。回调把日志更新为 1/2、巡检更新为 3、投递失败置 2，都会让 `count(status=0)` 减 1 → 互斥位自然释放。DB 计数在 admin 重启后也不丢失。

- [ ] **Step 3: 编译**

Run: `mvn -q -DskipTests install`
Expected: BUILD SUCCESS。

- [ ] **Step 4: 手工验证「触发立即返回 + 回调收账 + 无重复投递」**

```bash
# admin + samples 都在跑的前提下。jobId=3 是示例，换成你实际的 slowHandler 任务 id
# 1) 手动触发 slowHandler（15s 长任务），返回应立即，而非等 15s
time curl -s -X POST http://localhost:8080/job/3/trigger
# Expected: 立即返回 {"code":200,...}，time < 1s（投递 ack 即回）

# 2) 看该任务的日志：status 先从 0（在跑）→ 回调后变 1；只有 1 条
curl -s "http://localhost:8080/joblog/page?page=1&size=10&jobId=3" | python -c "import sys,json;[print(r['id'],r['status'],r['triggerType'],r['handleMsg']) for r in json.load(sys.stdin)['records']]"

# 3) 连发 3 次并发手动触发 → SINGLE 下应 1 条分发 + 2 条「被阻塞」(status=3, address 为空)
curl -s -X POST http://localhost:8080/job/3/trigger >/dev/null &
curl -s -X POST http://localhost:8080/job/3/trigger >/dev/null &
curl -s -X POST http://localhost:8080/job/3/trigger >/dev/null &
sleep 16
curl -s "http://localhost:8080/joblog/page?page=1&size=10&jobId=3" | python -c "import sys,json;[print(r['id'],r['status'],r['executorAddress'],(r['handleMsg'] or '')[:20]) for r in json.load(sys.stdin)['records']]"
```

Expected: 3 条并发中 1 条 `status` 终态为 1（真实成功，且是回调写的）、2 条 `status=3` + `executorAddress` 为空 + msg「任务上一次执行尚未结束」。

- [ ] **Step 5: Commit**

```bash
git add ww-job-admin/src/main/java/com/wwjob/admin/service/JobTriggerServiceImpl.java ww-job-admin/src/main/java/com/wwjob/admin/mapper/JobInfoMapper.java
git commit -m "feat: admin 触发改事务决策+锁外投递，SINGLE 互斥改 DB status=0 计数"
```

---

### Task 5: admin 超时巡检 `JobLogTimeoutScanner`

**Files:**
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/service/JobLogTimeoutScanner.java`

**Interfaces:**
- Consumes: `JobLogMapper`、`JobInfoMapper`、`JobLog.STATUS_RUNNING/UNKNOWN`、`ReturnT.FAIL_CODE`、`JobInfo.getTimeout()`
- Produces: 每 30s 把「status=0 且超过 `timeout`（0→60s）阈值」的日志标记为 status=3。标记后互斥位（status 离开 0）自然释放。

- [ ] **Step 1: 写巡检组件**

```java
package com.wwjob.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wwjob.admin.entity.JobInfo;
import com.wwjob.admin.entity.JobLog;
import com.wwjob.admin.mapper.JobInfoMapper;
import com.wwjob.admin.mapper.JobLogMapper;
import com.wwjob.core.model.ReturnT;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author 王威
 * @version 1.0
 */

/**
 * 超时巡检：执行器宕机导致回调永不来的兜底。
 * 把 status=0 且超过阈值的日志标记为 status=3（结果未知）；阈值 = 任务 timeout，0 则默认 60s。
 * status 离开 0 的同时，DB 互斥位（status=0 计数）自然释放，调度不会被死执行器堵死。
 */
@Component
public class JobLogTimeoutScanner {
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;

    private final JobLogMapper jobLogMapper;
    private final JobInfoMapper jobInfoMapper;

    public JobLogTimeoutScanner(JobLogMapper jobLogMapper, JobInfoMapper jobInfoMapper) {
        this.jobLogMapper = jobLogMapper;
        this.jobInfoMapper = jobInfoMapper;
    }

    @Scheduled(fixedDelay = 30000)
    public void scan() {
        List<JobLog> runningLogs = jobLogMapper.selectList(new QueryWrapper<JobLog>()
                .eq("status", JobLog.STATUS_RUNNING));
        for (JobLog log : runningLogs) {
            JobInfo job = jobInfoMapper.selectById(log.getJobId());
            long timeoutSec = (job != null && job.getTimeout() != null && job.getTimeout() > 0)
                    ? job.getTimeout() : DEFAULT_TIMEOUT_SECONDS;
            LocalDateTime deadline = log.getTriggerTime().plusSeconds(timeoutSec);
            if (LocalDateTime.now().isAfter(deadline)) {
                log.setStatus(JobLog.STATUS_UNKNOWN);
                log.setHandleCode(ReturnT.FAIL_CODE);
                log.setHandleMsg("执行超时未收到回调，结果未知");
                log.setHandleTime(LocalDateTime.now());
                jobLogMapper.updateById(log);
            }
        }
    }
}
```

- [ ] **Step 2: 编译**

Run: `mvn -q -DskipTests install`
Expected: BUILD SUCCESS。

- [ ] **Step 3: 手工验证「超时标未知 + 迟到回调覆盖成真实结果」**

```bash
# admin + samples 都在跑。
# 建一个 timeout=5 的任务（job 4），handler=slowHandler（睡 15s），触发一次
curl -s -X POST http://localhost:8080/job -H "Content-Type: application/json" \
  -d '{"jobName":"timeout-test","jobGroupId":1,"handlerName":"slowHandler","cron":"0 0 * * * ?","routeStrategy":"round","retryCount":0,"blockStrategy":"SINGLE","timeout":5,"triggerStatus":0}'
# 手动触发
curl -s -X POST http://localhost:8080/job/4/trigger

# 6 秒后：巡检应已把日志标为 status=3（此时回调还没来，15s 才完成）
sleep 6
curl -s "http://localhost:8080/joblog/page?page=1&size=5&jobId=4" | python -c "import sys,json;[print(r['id'],r['status'],r['handleMsg']) for r in json.load(sys.stdin)['records']]"
# Expected: status=3，msg「执行超时未收到回调，结果未知」

# 再过 ~10s：迟到的回调应把日志覆盖成 status=1（最终一致）
sleep 12
curl -s "http://localhost:8080/joblog/page?page=1&size=5&jobId=4" | python -c "import sys,json;[print(r['id'],r['status'],r['handleMsg']) for r in json.load(sys.stdin)['records']]"
# Expected: status=1，msg 含「slow 执行成功」
```

- [ ] **Step 4: 清掉验证数据**

```bash
mysql -uroot -p'PASSWORD_REMOVED' ww_job -e "DELETE FROM job_log WHERE job_id=4; DELETE FROM job_info WHERE id=4;"
```

- [ ] **Step 5: Commit**

```bash
git add ww-job-admin/src/main/java/com/wwjob/admin/service/JobLogTimeoutScanner.java
git commit -m "feat: admin 超时巡检兜底（status=0 超阈值标未知，互斥位自然释放）"
```

---

### Task 6: 端到端全量验证 + 文档更新

**Files:**
- Modify: `README.md`（「执行语义与注意事项」更新为异步回调语义）
- Modify: `docs/backend-review.md`（思路一遗留边界 → 已由异步回调根治，标注）
- Modify: `docs/superpowers/specs/2026-08-26-async-callback-design.md`（顶部加「已实现 ✅」）

**Interfaces:**
- Consumes: 全部前序改动；对照 spec 第 7 节验证表逐条实测。

- [ ] **Step 1: 按 spec 验证表逐条实测**

```bash
# 前置：admin + samples 都在跑，三个 handler 都在（demoHandler / slowHandler）
# ① demoHandler 快任务 0/5：全 status=1
curl -s -X POST http://localhost:8080/job -H "Content-Type: application/json" \
  -d '{"jobName":"v2-demo","jobGroupId":1,"handlerName":"demoHandler","cron":"0/5 * * * * ?","routeStrategy":"round","retryCount":0,"blockStrategy":"SINGLE","triggerStatus":1}'
# 等 12s 后查该 job 日志：应全 status=1，且每条 handle_time 都在触发后 1s 内（回调写的时间）
# ② slowHandler 15s + timeout 默认(60s) + SINGLE + 0/5：>10s 任务不再被 admin 读超时打断——核心验证点
curl -s -X POST http://localhost:8080/job -H "Content-Type: application/json" \
  -d '{"jobName":"v2-slow","jobGroupId":1,"handlerName":"slowHandler","cron":"0/5 * * * * ?","routeStrategy":"round","retryCount":2,"blockStrategy":"SINGLE","triggerStatus":1}'
# 等 40s。注意：admin 现在 ack 即回，cron 每 5s 真触发；15s 慢任务在跑期间，中间 2 个 tick 被 SINGLE 拦下记 status=3「被阻塞」。
# 期望：分发日志全部 status=1（回调写、handle_time ≈ trigger+15s，绝无 status=3 超时）；被阻塞日志 status=3 且 executorAddress 为空
# ③ kill 执行器兜底：跑一个 slowHandler 任务，跑 2s 后 kill 8081 进程，等 70s
#    期望：无回调 → 巡检标 status=3；重启 samples 后下一 tick 能再次派发（互斥位已释放）
# ④ 并发狂刷 /run：观察部分返回「执行器繁忙」（有界队列生效，可选人工压）
```

- [ ] **Step 2: 核对执行器侧「无重复执行」**

```bash
# ⑤ retryCount=2 但超时任务不重复：对照分发数 vs 执行器实际跑的次数
#    执行器日志（/tmp/ww-samples.log）里 grep -ac "logId=" 应 ≈ 分发数（而非 3 倍）
#    且无重复 logId：grep -ao "logId=[0-9]*" | sort | uniq -d 应为空
```

- [ ] **Step 3: 更新 README「执行语义与注意事项」**

把第 2/3/4 条改写为异步回调语义：
- 超时不再由 admin 侧 10s 读超时触发，而是「投递 ack + 执行器回调」；`timeout` 字段成为执行超时阈值（0=默认 60s）
- status=3 现在只表示「超时未收到回调」（执行器可能真挂了），**迟到回调会覆盖成真实结果**（最终一致）
- SINGLE 互斥位 = DB 中 `status=0` 日志，admin 重启不丢、天然支持多 admin 实例
- retryCount 只管投递阶段；ack 后不因执行结果重试

- [ ] **Step 4: 更新 docs/backend-review.md**

把「思路一」章节的「遗留边界」标注为：已由 Phase 2 异步回调根治（同步阻塞等待 → 投递+回调）；互斥位从内存 Set → DB status=0 计数，admin 重启不丢。

- [ ] **Step 5: spec 顶部加实现状态**

`2026-08-26-async-callback-design.md` 状态行改为：`状态：已实现（2026-08-27，见 plan 2026-08-27-async-callback.md）`。

- [ ] **Step 6: 提交文档**

```bash
git add README.md docs/backend-review.md docs/superpowers/specs/2026-08-26-async-callback-design.md
git commit -m "docs: 异步回调执行语义 + 审查记录更新 + spec 标记已实现"
```

- [ ] **Step 7: 合并回 main 并推送**

```bash
# 在 feat/phase2-async-callback 分支上：
git checkout main
git merge feat/phase2-async-callback
git push origin main
# 可选：同步远程分支
git push origin feat/phase2-async-callback
```

---

## 自检记录

- **Spec 覆盖**：D1 巡检兜底 → Task 5；D2 迟到回调最终一致 → Task 2（覆盖更新）；D3 DB status=0 计数互斥 → Task 4；D4 retryCount 只管投递 → Task 4 dispatch；D5 有界队列快速失败 → Task 3；D6 回调退避重试 → Task 3。验证表 → Task 6。非目标（集群/前端等）未列入。
- **无占位符**：每个代码步骤均含完整可编译代码。
- **类型一致**：`CallbackParam(logId, handleCode, handleMsg, handleTime)` 在 Task 1 定义、Task 2 接收、Task 3 构造，签名一致；`selectByIdForUpdate(long)` 在 Task 4 定义并唯一使用；`JobLog.STATUS_*` 常量贯穿。
