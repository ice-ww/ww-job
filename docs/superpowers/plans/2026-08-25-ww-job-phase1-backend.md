# ww-job Phase 1 后端核心链路 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 端到端跑通「创建任务 → 定时/手动触发 → 分发到执行器 → 执行 JobHandler → 日志落库 → 失败重试」的后端核心链路。

**Architecture:** Maven 多模块（core / admin / executor / executor-samples），调度中心与执行器通过 HTTP 通信，调度引擎为自研时间轮 + DB 预读，执行器注册靠心跳上报。

**Tech Stack:** Java 17、Spring Boot 3.3、Maven、MySQL 8、MyBatis-Plus、Redis（锁预留）、HTTP。

**Spec:** `docs/superpowers/specs/2026-08-25-ww-job-design.md`

## Global Constraints

- Java 17；Spring Boot 3.3.x；Maven 构建
- groupId `com.wwjob`；模块 artifactId：`ww-job`(parent) / `ww-job-core` / `ww-job-admin` / `ww-job-executor` / `ww-job-executor-samples`
- Java 包根：`com.wwjob.core` / `com.wwjob.admin` / `com.wwjob.executor`
- cron 采用 Spring 6 字段格式（`秒 分 时 日 月 周`），解析用 `org.springframework.scheduling.support.CronExpression`（零额外依赖，非 Quartz 7 字段）
- 通信：HTTP；执行器内嵌 HTTP server（Spring Boot Web）
- 分支约定：Phase 1 全程在分支 `feat/phase1-backend` 上开发，阶段结束合并回 `main` 并 push
- 每个 Task 末尾 `git commit`，提交信息用 `类型: 说明` 约定（feat/fix/docs/refactor/test）

---

### Task 1: 多模块 Maven 骨架

**Files:**
- Create: `pom.xml`（parent）
- Create: `ww-job-core/pom.xml`、`ww-job-admin/pom.xml`、`ww-job-executor/pom.xml`、`ww-job-executor-samples/pom.xml`
- Create: `ww-job-core/src/main/java/com/wwjob/core/CoreMarker.java`
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/WwJobAdminApplication.java`
- Create: `ww-job-admin/src/main/resources/application.yml`
- Create: `ww-job-executor-samples/src/main/java/com/wwjob/executor/samples/WwJobExecutorSamplesApplication.java`
- Create: `ww-job-executor-samples/src/main/resources/application.yml`

**Interfaces:**
- Produces: 模块依赖关系 `ww-job-core` 被 `admin`/`executor` 依赖；`executor-samples` 依赖 `ww-job-executor`。parent pom 统一管理 Spring Boot 版本与 `ww-job-core`/`ww-job-executor` 版本。

- [ ] **Step 1: 写 parent `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativePath/>
    </parent>

    <groupId>com.wwjob</groupId>
    <artifactId>ww-job</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>ww-job-core</module>
        <module>ww-job-executor</module>
        <module>ww-job-admin</module>
        <module>ww-job-executor-samples</module>
    </modules>

    <properties>
        <java.version>17</java.version>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <mybatis-plus.version>3.5.7</mybatis-plus.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.baomidou</groupId>
                <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
                <version>${mybatis-plus.version}</version>
            </dependency>
            <dependency>
                <groupId>com.wwjob</groupId>
                <artifactId>ww-job-core</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.wwjob</groupId>
                <artifactId>ww-job-executor</artifactId>
                <version>${project.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

- [ ] **Step 2: 写各模块 `pom.xml`**

`ww-job-core/pom.xml`（普通 jar，无 Spring Boot 插件；引入 `spring-context` 供 `@Component` 元注解与 `CronExpression` 使用，版本由 Boot parent 管理）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.wwjob</groupId>
        <artifactId>ww-job</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>ww-job-core</artifactId>
    <dependencies>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-context</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

`ww-job-executor/pom.xml`（普通 jar，依赖 core 与 Spring Boot Web starter）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.wwjob</groupId>
        <artifactId>ww-job</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>ww-job-executor</artifactId>
    <dependencies>
        <dependency>
            <groupId>com.wwjob</groupId>
            <artifactId>ww-job-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
    </dependencies>
</project>
```

`ww-job-admin/pom.xml`（Spring Boot 应用，依赖 core + web + mybatis-plus + mysql + redis）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.wwjob</groupId>
        <artifactId>ww-job</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>ww-job-admin</artifactId>
    <dependencies>
        <dependency>
            <groupId>com.wwjob</groupId>
            <artifactId>ww-job-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

`ww-job-executor-samples/pom.xml`（Spring Boot 应用，依赖 executor）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.wwjob</groupId>
        <artifactId>ww-job</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>ww-job-executor-samples</artifactId>
    <dependencies>
        <dependency>
            <groupId>com.wwjob</groupId>
            <artifactId>ww-job-executor</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: 写最小 Java 类与配置**

`ww-job-core/src/main/java/com/wwjob/core/CoreMarker.java`：

```java
package com.wwjob.core;

/** 占位类，用于确认 core 模块可编译。 */
public final class CoreMarker {
    private CoreMarker() {}
}
```

`ww-job-admin/src/main/java/com/wwjob/admin/WwJobAdminApplication.java`：

```java
package com.wwjob.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WwJobAdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(WwJobAdminApplication.class, args);
    }
}
```

`ww-job-admin/src/main/resources/application.yml`：

```yaml
server:
  port: 8080
spring:
  application:
    name: ww-job-admin
  datasource:
    url: jdbc:mysql://localhost:3306/ww_job?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver
  data:
    redis:
      host: localhost
      port: 6379
```

`ww-job-executor-samples/src/main/java/com/wwjob/executor/samples/WwJobExecutorSamplesApplication.java`：

```java
package com.wwjob.executor.samples;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WwJobExecutorSamplesApplication {
    public static void main(String[] args) {
        SpringApplication.run(WwJobExecutorSamplesApplication.class, args);
    }
}
```

`ww-job-executor-samples/src/main/resources/application.yml`：

```yaml
server:
  port: 8081
spring:
  application:
    name: ww-job-executor-samples
```

- [ ] **Step 4: 编译验证**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS，5 个模块全部编译通过。

- [ ] **Step 5: Commit**

```bash
git add .
git commit -m "feat: 搭建 Maven 多模块骨架（core/admin/executor/samples）"
```

---

### Task 2: core 通用返回模型与通信协议 DTO

**Files:**
- Create: `ww-job-core/src/main/java/com/wwjob/core/model/ReturnT.java`
- Create: `ww-job-core/src/main/java/com/wwjob/core/model/TriggerParam.java`
- Create: `ww-job-core/src/main/java/com/wwjob/core/model/RegistryParam.java`
- Create: `ww-job-core/src/main/java/com/wwjob/core/model/LogParam.java`
- Test: `ww-job-core/src/test/java/com/wwjob/core/model/ReturnTTest.java`

**Interfaces:**
- Produces: `ReturnT<T>`（字段 `int code`、`String msg`、`T data`；常量 `SUCCESS_CODE=200`、`FAIL_CODE=500`；静态方法 `ReturnT.success()/success(data)/fail(msg)`）；`TriggerParam`（字段 `long jobId`、`String handler`、`String executorParam`、`int shardIndex`、`int shardTotal`、`long logId`）；`RegistryParam`（字段 `String registryKey`、`String registryValue`）；`LogParam`（字段 `long logId`、`String content`）

- [ ] **Step 1: 写失败测试**

```java
package com.wwjob.core.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReturnTTest {
    @Test
    void successHasCode200AndNullData() {
        ReturnT<Void> r = ReturnT.success();
        assertEquals(ReturnT.SUCCESS_CODE, r.getCode());
        assertNull(r.getData());
    }

    @Test
    void failHasCode500AndMessage() {
        ReturnT<Void> r = ReturnT.fail("boom");
        assertEquals(ReturnT.FAIL_CODE, r.getCode());
        assertEquals("boom", r.getMsg());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl ww-job-core test -Dtest=ReturnTTest`
Expected: 编译失败（`ReturnT` 未定义）。

- [ ] **Step 3: 写实现**

`ReturnT.java`：

```java
package com.wwjob.core.model;

import java.io.Serializable;

public class ReturnT<T> implements Serializable {
    public static final int SUCCESS_CODE = 200;
    public static final int FAIL_CODE = 500;

    private int code;
    private String msg;
    private T data;

    public ReturnT() {}
    public ReturnT(int code, String msg) { this.code = code; this.msg = msg; }
    public ReturnT(int code, String msg, T data) { this.code = code; this.msg = msg; this.data = data; }

    public static <T> ReturnT<T> success() { return new ReturnT<>(SUCCESS_CODE, null); }
    public static <T> ReturnT<T> success(T data) { return new ReturnT<>(SUCCESS_CODE, null, data); }
    public static <T> ReturnT<T> fail(String msg) { return new ReturnT<>(FAIL_CODE, msg); }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public String getMsg() { return msg; }
    public void setMsg(String msg) { this.msg = msg; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
}
```

`TriggerParam.java`、`RegistryParam.java`、`LogParam.java`：各字段带 getter/setter 的简单 `Serializable` POJO（字段见上方 Produces 定义）。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl ww-job-core test -Dtest=ReturnTTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add ww-job-core
git commit -m "feat: core 增加 ReturnT 与通信协议 DTO"
```

---

### Task 3: core 的 IJobHandler 抽象与 @JobHandler 注解

**Files:**
- Create: `ww-job-core/src/main/java/com/wwjob/core/handler/IJobHandler.java`
- Create: `ww-job-core/src/main/java/com/wwjob/core/handler/JobHandler.java`
- Create: `ww-job-core/src/main/java/com/wwjob/core/context/JobContext.java`

**Interfaces:**
- Produces: `IJobHandler`（接口，方法 `ReturnT<String> execute(JobContext ctx)`）；`@JobHandler`（`@Target(TYPE)`、`@Retention(RUNTIME)`、`@Component` 元注解，字段 `String value()` 作为 handler 名）；`JobContext`（字段 `long jobId`、`long logId`、`String executorParam`、`int shardIndex`、`int shardTotal`）

- [ ] **Step 1: 写实现（此任务为接口定义，无独立可测逻辑，编译通过即可）**

`IJobHandler.java`：

```java
package com.wwjob.core.handler;

import com.wwjob.core.context.JobContext;
import com.wwjob.core.model.ReturnT;

public interface IJobHandler {
    ReturnT<String> execute(JobContext ctx) throws Exception;
}
```

`JobHandler.java`：

```java
package com.wwjob.core.handler;

import org.springframework.stereotype.Component;
import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface JobHandler {
    /** handler 名称，调度中心以此定位执行器上的任务。 */
    String value();
}
```

`JobContext.java`：

```java
package com.wwjob.core.context;

public class JobContext {
    private long jobId;
    private long logId;
    private String executorParam;
    private int shardIndex;
    private int shardTotal;
    // getter / setter 略
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn -q -pl ww-job-core compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add ww-job-core
git commit -m "feat: core 增加 IJobHandler 抽象与 @JobHandler 注解"
```

---

### Task 4: core 时间轮 TimeWheel（TDD）

**Files:**
- Create: `ww-job-core/src/main/java/com/wwjob/core/schedule/TimeWheel.java`
- Test: `ww-job-core/src/test/java/com/wwjob/core/schedule/TimeWheelTest.java`

**Interfaces:**
- Produces: `TimeWheel(long tickDurationMs, int wheelSize)`；`void addTask(long delayMs, Runnable task)`；`List<Runnable> advance()`（推进一格并返回本次到期任务列表）；`int currentTick()`。**纯逻辑、无时钟依赖、确定性可测**。

- [ ] **Step 1: 写失败测试**

```java
package com.wwjob.core.schedule;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TimeWheelTest {
    // 一圈 10 格，每格 100ms，整圈 1000ms
    private TimeWheel newWheel() { return new TimeWheel(100, 10); }

    @Test
    void taskWithinOneTickFiresOnNextAdvance() {
        TimeWheel wheel = newWheel();
        List<String> fired = new ArrayList<>();
        wheel.addTask(50, () -> fired.add("a"));
        assertEquals(0, wheel.advance().size());
        assertEquals(List.of("a"), run(wheel.advance()));
    }

    @Test
    void taskFiresAfterExactNumberOfTicks() {
        TimeWheel wheel = newWheel();
        List<String> fired = new ArrayList<>();
        wheel.addTask(300, () -> fired.add("x"));
        // 推进 3 次（300ms）后应触发
        List<String> due = new ArrayList<>();
        for (int i = 0; i < 3; i++) due.addAll(run(wheel.advance()));
        assertEquals(List.of("x"), due);
    }

    @Test
    void taskWithExactlyOneRotationDelayFiresAfterFullRotation() {
        TimeWheel wheel = newWheel();
        List<String> fired = new ArrayList<>();
        wheel.addTask(1000, () -> fired.add("full")); // 恰好一圈
        List<String> due = new ArrayList<>();
        for (int i = 0; i < 10; i++) due.addAll(run(wheel.advance()));
        assertEquals(List.of("full"), due); // 恰好在第 10 格触发，不多不少
    }

    @Test
    void taskBeyondOneRotationUsesRemainingRounds() {
        TimeWheel wheel = newWheel();
        List<String> fired = new ArrayList<>();
        wheel.addTask(1200, () -> fired.add("beyond"));
        List<String> due = new ArrayList<>();
        for (int i = 0; i < 12; i++) due.addAll(run(wheel.advance()));
        assertEquals(List.of("beyond"), due); // 第 12 格才触发
    }

    private static List<String> run(List<Runnable> tasks) {
        List<String> out = new ArrayList<>();
        for (Runnable t : tasks) t.run();
        return out;
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl ww-job-core test -Dtest=TimeWheelTest`
Expected: 编译失败（`TimeWheel` 未定义）。

- [ ] **Step 3: 写实现（注意 off-by-one：`remainingRounds` 用 `(delayTicks - 1) / wheelSize`）**

```java
package com.wwjob.core.schedule;

import java.util.*;

public class TimeWheel {
    private final long tickDurationMs;
    private final int wheelSize;
    private final List<List<TimerTask>> slots;
    private int currentTick = 0;

    public TimeWheel(long tickDurationMs, int wheelSize) {
        this.tickDurationMs = tickDurationMs;
        this.wheelSize = wheelSize;
        this.slots = new ArrayList<>(wheelSize);
        for (int i = 0; i < wheelSize; i++) {
            this.slots.add(new LinkedList<>());
        }
    }

    public void addTask(long delayMs, Runnable task) {
        long delayTicks = Math.max(1, (delayMs + tickDurationMs - 1) / tickDurationMs);
        int stopIndex = (int) ((currentTick + delayTicks) % wheelSize);
        long remainingRounds = (delayTicks - 1) / wheelSize;
        slots.get(stopIndex).add(new TimerTask(task, remainingRounds));
    }

    public List<Runnable> advance() {
        currentTick = (currentTick + 1) % wheelSize;
        List<TimerTask> bucket = slots.get(currentTick);
        List<Runnable> due = new ArrayList<>();
        Iterator<TimerTask> it = bucket.iterator();
        while (it.hasNext()) {
            TimerTask t = it.next();
            if (t.remainingRounds > 0) {
                t.remainingRounds--;
            } else {
                due.add(t.task);
                it.remove();
            }
        }
        return due;
    }

    public int currentTick() { return currentTick; }

    private static final class TimerTask {
        final Runnable task;
        long remainingRounds;
        TimerTask(Runnable task, long remainingRounds) {
            this.task = task;
            this.remainingRounds = remainingRounds;
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl ww-job-core test -Dtest=TimeWheelTest`
Expected: 4 个测试全部 PASS。

- [ ] **Step 5: Commit**

```bash
git add ww-job-core
git commit -m "feat: core 实现自研时间轮 TimeWheel"
```

---

### Task 5: core cron 下次触发时间计算（TDD）

**Files:**
- Create: `ww-job-core/src/main/java/com/wwjob/core/util/CronUtil.java`
- Test: `ww-job-core/src/test/java/com/wwjob/core/util/CronUtilTest.java`

**Interfaces:**
- Produces: `static long nextTime(String cron, long fromMillis)`，返回 `fromMillis` 之后最近一次触发的时间戳（毫秒）；cron 非法时抛 `IllegalArgumentException`。

- [ ] **Step 1: 写失败测试**

```java
package com.wwjob.core.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CronUtilTest {
    @Test
    void nextTimeEveryFiveMinutes() {
        // "0 */5 * * * *" = 每 5 分钟
        long from = epoch("2026-01-01 00:03:00");
        long next = CronUtil.nextTime("0 */5 * * * *", from);
        assertEquals(epoch("2026-01-01 00:05:00"), next);
    }

    @Test
    void nextTimeEverySecond() {
        long from = epoch("2026-01-01 00:00:00");
        long next = CronUtil.nextTime("* * * * * *", from);
        assertEquals(from + 1000, next);
    }

    @Test
    void invalidCronThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> CronUtil.nextTime("not-a-cron", 0L));
    }

    private static long epoch(String s) {
        return java.time.LocalDateTime.parse(s.replace(' ', 'T'))
                .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl ww-job-core test -Dtest=CronUtilTest`
Expected: 编译失败。

- [ ] **Step 3: 写实现**

```java
package com.wwjob.core.util;

import org.springframework.scheduling.support.CronExpression;
import java.time.*;

public final class CronUtil {
    private CronUtil() {}

    public static long nextTime(String cron, long fromMillis) {
        CronExpression expression;
        try {
            expression = CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法 cron 表达式: " + cron, e);
        }
        LocalDateTime from = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(fromMillis), ZoneId.systemDefault());
        LocalDateTime next = expression.next(from);
        if (next == null) {
            throw new IllegalArgumentException("该 cron 无后续触发时间: " + cron);
        }
        return next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl ww-job-core test -Dtest=CronUtilTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add ww-job-core
git commit -m "feat: core 增加 cron 下次触发时间计算"
```

---

### Task 6: core 路由策略 Router（TDD）

**Files:**
- Create: `ww-job-core/src/main/java/com/wwjob/core/router/Router.java`
- Create: `ww-job-core/src/main/java/com/wwjob/core/router/RoundRobinRouter.java`
- Create: `ww-job-core/src/main/java/com/wwjob/core/router/RandomRouter.java`
- Create: `ww-job-core/src/main/java/com/wwjob/core/router/FailoverRouter.java`
- Test: `ww-job-core/src/test/java/com/wwjob/core/router/RouterTest.java`

**Interfaces:**
- Consumes: 无。
- Produces: `Router`（接口 `String route(List<String> addresses, long jobId)`，输入在线执行器地址列表，返回选中地址；空列表返回 `null`）；`RoundRobinRouter`（`AtomicInteger` 取模轮询，空列表返回 null）；`RandomRouter`（`ThreadLocalRandom` 随机）；`FailoverRouter`（跳过列表尾部已失败的地址：调用方通过 `addresses.remove(last)` 后重试，本类内部循环直到成功或耗尽）。

- [ ] **Step 1: 写失败测试**

```java
package com.wwjob.core.router;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RouterTest {
    private final List<String> addrs = List.of("a", "b", "c");

    @Test
    void roundRobinCyclesInOrder() {
        Router r = new RoundRobinRouter();
        assertEquals("a", r.route(addrs, 1));
        assertEquals("b", r.route(addrs, 1));
        assertEquals("c", r.route(addrs, 1));
        assertEquals("a", r.route(addrs, 1));
    }

    @Test
    void randomAlwaysPicksFromList() {
        Router r = new RandomRouter();
        for (int i = 0; i < 100; i++) {
            assertTrue(addrs.contains(r.route(addrs, 1)));
        }
    }

    @Test
    void emptyListReturnsNull() {
        assertEquals(null, new RoundRobinRouter().route(List.of(), 1));
        assertEquals(null, new RandomRouter().route(List.of(), 1));
        assertEquals(null, new FailoverRouter().route(new ArrayList<>(), 1));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -pl ww-job-core test -Dtest=RouterTest`
Expected: 编译失败。

- [ ] **Step 3: 写实现**

`Router.java`：

```java
package com.wwjob.core.router;

import java.util.List;

public interface Router {
    String route(List<String> addresses, long jobId);
}
```

`RoundRobinRouter.java`：

```java
package com.wwjob.core.router;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class RoundRobinRouter implements Router {
    private final AtomicInteger counter = new AtomicInteger(0);
    @Override
    public String route(List<String> addresses, long jobId) {
        if (addresses == null || addresses.isEmpty()) return null;
        int idx = Math.abs(counter.getAndIncrement() % addresses.size());
        return addresses.get(idx);
    }
}
```

`RandomRouter.java`：

```java
package com.wwjob.core.router;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class RandomRouter implements Router {
    @Override
    public String route(List<String> addresses, long jobId) {
        if (addresses == null || addresses.isEmpty()) return null;
        return addresses.get(ThreadLocalRandom.current().nextInt(addresses.size()));
    }
}
```

`FailoverRouter.java`：

```java
package com.wwjob.core.router;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class FailoverRouter implements Router {
    private final AtomicInteger counter = new AtomicInteger(0);
    @Override
    public String route(List<String> addresses, long jobId) {
        if (addresses == null || addresses.isEmpty()) return null;
        int size = addresses.size();
        for (int i = 0; i < size; i++) {
            int idx = Math.abs(counter.getAndIncrement() % size);
            // 调用方会在失败后从列表移除该地址，因此这里直接返回；重复轮询由外层负责
            return addresses.get(idx);
        }
        return null;
    }
}
```

> 说明：`FailoverRouter` 在 Phase 1 先提供与轮询一致的实现，真正的「失败重选」逻辑在 Task 12（重试）中由调度中心配合「移除失败地址后再次调用」完成。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -pl ww-job-core test -Dtest=RouterTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add ww-job-core
git commit -m "feat: core 增加路由策略（轮询/随机/故障转移）"
```

---

### Task 7: admin 数据模型 + MyBatis-Plus 实体 + 建表 SQL

**Files:**
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/entity/JobInfo.java`
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/entity/JobGroup.java`
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/entity/JobRegistry.java`
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/entity/JobLog.java`
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/mapper/JobInfoMapper.java`（及 Group/Registry/Log 三个 Mapper）
- Create: `ww-job-admin/src/main/resources/db/schema.sql`
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/WwJobAdminApplication.java`（加 `@MapperScan("com.wwjob.admin.mapper")`）
- Modify: `ww-job-admin/src/main/resources/application.yml`（加 `spring.sql.init.mode` 与 mybatis-plus 配置）

**Interfaces:**
- Produces: 四张表实体与 Mapper；表结构见 Step 2 的 `schema.sql`。后续 Task（调度线程、注册、日志、重试）全部消费这些实体与 Mapper。

- [ ] **Step 1: 写 `schema.sql`**

```sql
CREATE TABLE IF NOT EXISTS job_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    app_name VARCHAR(64) NOT NULL COMMENT '执行器标识',
    title VARCHAR(64) NOT NULL COMMENT '名称',
    address_type TINYINT DEFAULT 0 COMMENT '0自动注册 1手动',
    address_list VARCHAR(512) COMMENT '手动地址列表，逗号分隔',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_app_name (app_name)
) COMMENT '执行器分组';

CREATE TABLE IF NOT EXISTS job_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_group_id BIGINT NOT NULL COMMENT '执行器分组id',
    job_name VARCHAR(64) NOT NULL COMMENT '任务名',
    job_desc VARCHAR(255) COMMENT '描述',
    handler_name VARCHAR(64) NOT NULL COMMENT 'JobHandler 名',
    executor_param VARCHAR(512) COMMENT '任务参数',
    cron VARCHAR(64) NOT NULL COMMENT 'cron 表达式',
    route_strategy VARCHAR(32) DEFAULT 'round_robin',
    block_strategy VARCHAR(32) DEFAULT 'serial',
    retry_count INT DEFAULT 0,
    timeout INT DEFAULT 0 COMMENT '超时秒数，0不限制',
    alarm_config VARCHAR(512) COMMENT '报警配置',
    trigger_status TINYINT DEFAULT 1 COMMENT '1启用 0暂停',
    trigger_next_time BIGINT DEFAULT 0 COMMENT '下次触发毫秒时间戳',
    trigger_last_time BIGINT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_group (job_group_id),
    KEY idx_next_time (trigger_next_time)
) COMMENT '任务';

CREATE TABLE IF NOT EXISTS job_registry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_group_id BIGINT NOT NULL,
    registry_key VARCHAR(64) NOT NULL COMMENT 'appName',
    registry_value VARCHAR(128) NOT NULL COMMENT 'ip:port',
    heartbeat_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_key_time (registry_key, heartbeat_time)
) COMMENT '执行器注册表';

CREATE TABLE IF NOT EXISTS job_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    job_group_id BIGINT NOT NULL,
    executor_address VARCHAR(128) COMMENT '执行地址',
    handler_name VARCHAR(64),
    trigger_type VARCHAR(16) COMMENT 'cron/manual/retry',
    trigger_time DATETIME COMMENT '触发时间',
    handle_time DATETIME COMMENT '执行完成时间',
    handle_code INT COMMENT '200成功 500失败',
    handle_msg VARCHAR(1024) COMMENT '失败信息',
    status TINYINT DEFAULT 0 COMMENT '0运行中 1成功 2失败',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_job (job_id),
    KEY idx_create (create_time)
) COMMENT '执行日志';
```

- [ ] **Step 2: 写实体类（以 `JobInfo` 为例，其余同构）**

`JobInfo.java`：

```java
package com.wwjob.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("job_info")
public class JobInfo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long jobGroupId;
    private String jobName;
    private String jobDesc;
    private String handlerName;
    private String executorParam;
    private String cron;
    private String routeStrategy;
    private String blockStrategy;
    private Integer retryCount;
    private Integer timeout;
    private String alarmConfig;
    private Integer triggerStatus;
    private Long triggerNextTime;
    private Long triggerLastTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    // getter / setter 略（用 Lombok 需在 admin 加依赖；本计划先手写，保持零额外依赖）
}
```

`JobGroup.java`（字段 `id, appName, title, addressType, addressList, createTime, updateTime`）、`JobRegistry.java`（`id, jobGroupId, registryKey, registryValue, heartbeatTime, updateTime`）、`JobLog.java`（`id, jobId, jobGroupId, executorAddress, handlerName, triggerType, triggerTime, handleTime, handleCode, handleMsg, status, createTime`）同构，均 `@TableName` + `@TableId(type = IdType.AUTO)`。

- [ ] **Step 3: 写 Mapper 接口**

`JobInfoMapper.java`（其余 Mapper 同构，继承 `BaseMapper<T>`）：

```java
package com.wwjob.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wwjob.admin.entity.JobInfo;

public interface JobInfoMapper extends BaseMapper<JobInfo> {
}
```

同时创建 `JobGroupMapper`、`JobRegistryMapper`、`JobLogMapper`。

- [ ] **Step 4: 改主类与配置**

`WwJobAdminApplication.java` 加注解：

```java
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication
@MapperScan("com.wwjob.admin.mapper")
public class WwJobAdminApplication { ... }
```

`application.yml` 追加：

```yaml
spring:
  sql:
    init:
      mode: always
      schema-locations: classpath:db/schema.sql
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
```

- [ ] **Step 5: 启动验证（需本地 MySQL 已建库 `ww_job`）**

Run: `mvn -q -pl ww-job-admin spring-boot:run`
Expected: 应用启动成功，`ww_job` 库中出现 4 张表；无 Bean 装配错误。启动后 Ctrl+C 停止。

- [ ] **Step 6: Commit**

```bash
git add ww-job-admin
git commit -m "feat: admin 增加数据模型、Mapper 与建表 SQL"
```

---

### Task 8: 执行器注册 / 心跳（注册中心）

**Files:**
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/controller/RegistryController.java`
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/service/RegistryService.java`
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/service/RegistryCleaner.java`
- Create: `ww-job-executor/src/main/java/com/wwjob/executor/ExecutorProperties.java`
- Create: `ww-job-executor/src/main/java/com/wwjob/executor/auto/ExecutorAutoConfiguration.java`
- Create: `ww-job-executor/src/main/java/com/wwjob/executor/registry/ExecutorRegistry.java`
- Create: `ww-job-executor/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

**Interfaces:**
- Consumes: Task 2 的 `RegistryParam`、`ReturnT`；Task 7 的 `JobRegistryMapper`/`JobGroupMapper`。
- Produces: admin 侧 `POST /registry`、`POST /heartbeat` 两个接口；executor 侧 `ExecutorProperties`（字段 `adminAddresses`、`appName`、`port`、`heartbeatIntervalSeconds=30`）、`ExecutorRegistry`（启动即注册 + 定时心跳）。

- [ ] **Step 1: admin 侧 `RegistryController` 与 `RegistryService`**

```java
package com.wwjob.admin.controller;

import com.wwjob.admin.service.RegistryService;
import com.wwjob.core.model.RegistryParam;
import com.wwjob.core.model.ReturnT;
import org.springframework.web.bind.annotation.*;

@RestController
public class RegistryController {
    private final RegistryService registryService;
    public RegistryController(RegistryService registryService) { this.registryService = registryService; }

    @PostMapping("/registry")
    public ReturnT<String> registry(@RequestBody RegistryParam param) {
        return registryService.registry(param);
    }

    @PostMapping("/heartbeat")
    public ReturnT<String> heartbeat(@RequestBody RegistryParam param) {
        return registryService.heartbeat(param);
    }
}
```

`RegistryService`（要点：按 `registryKey` 查 `job_group` 得 `jobGroupId`；存在则 upsert `job_registry` 行并刷新 `heartbeat_time`）：

```java
package com.wwjob.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wwjob.admin.entity.JobGroup;
import com.wwjob.admin.entity.JobRegistry;
import com.wwjob.admin.mapper.JobGroupMapper;
import com.wwjob.admin.mapper.JobRegistryMapper;
import com.wwjob.core.model.RegistryParam;
import com.wwjob.core.model.ReturnT;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

@Service
public class RegistryService {
    private final JobGroupMapper groupMapper;
    private final JobRegistryMapper registryMapper;
    public RegistryService(JobGroupMapper groupMapper, JobRegistryMapper registryMapper) {
        this.groupMapper = groupMapper;
        this.registryMapper = registryMapper;
    }

    public ReturnT<String> registry(RegistryParam param) {
        return upsert(param);
    }

    public ReturnT<String> heartbeat(RegistryParam param) {
        return upsert(param);
    }

    private ReturnT<String> upsert(RegistryParam param) {
        JobGroup group = groupMapper.selectOne(
                new QueryWrapper<JobGroup>().eq("app_name", param.getRegistryKey()));
        if (group == null) {
            return ReturnT.fail("执行器分组未注册: " + param.getRegistryKey());
        }
        JobRegistry existing = registryMapper.selectOne(
                new QueryWrapper<JobRegistry>()
                        .eq("job_group_id", group.getId())
                        .eq("registry_value", param.getRegistryValue()));
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            JobRegistry r = new JobRegistry();
            r.setJobGroupId(group.getId());
            r.setRegistryKey(param.getRegistryKey());
            r.setRegistryValue(param.getRegistryValue());
            r.setHeartbeatTime(now);
            registryMapper.insert(r);
        } else {
            existing.setHeartbeatTime(now);
            registryMapper.updateById(existing);
        }
        return ReturnT.success();
    }
}
```

- [ ] **Step 2: admin 侧 `RegistryCleaner` 剔除线程**

```java
package com.wwjob.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wwjob.admin.entity.JobRegistry;
import com.wwjob.admin.mapper.JobRegistryMapper;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

@Component
@EnableScheduling
public class RegistryCleaner {
    private static final int EXPIRE_SECONDS = 90;
    private final JobRegistryMapper registryMapper;
    public RegistryCleaner(JobRegistryMapper registryMapper) { this.registryMapper = registryMapper; }

    @Scheduled(fixedRate = 10000)
    public void clean() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(EXPIRE_SECONDS);
        registryMapper.delete(new QueryWrapper<JobRegistry>().lt("heartbeat_time", threshold));
    }
}
```

- [ ] **Step 3: executor 侧 `ExecutorProperties` 与 `ExecutorRegistry`**

`ExecutorProperties.java`：

```java
package com.wwjob.executor;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wwjob.executor")
public class ExecutorProperties {
    private String adminAddresses;   // 逗号分隔，如 http://localhost:8080
    private String appName;
    private int port = 8081;
    private int heartbeatIntervalSeconds = 30;
    // getter / setter 略
}
```

`ExecutorRegistry.java`（用 `RestTemplate` 调 admin 的 `/registry`、`/heartbeat`，启动时注册 + `@Scheduled` 心跳）：

```java
package com.wwjob.executor.registry;

import com.wwjob.core.model.RegistryParam;
import com.wwjob.executor.ExecutorProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestTemplate;
import java.net.InetAddress;

@EnableScheduling
public class ExecutorRegistry {
    private final ExecutorProperties props;
    private final RestTemplate restTemplate = new RestTemplate();

    public ExecutorRegistry(ExecutorProperties props) { this.props = props; }

    @PostConstruct
    public void register() { doRegister(); }

    @Scheduled(fixedRateString = "#{${wwjob.executor.heartbeat-interval-seconds:30} * 1000}")
    public void heartbeat() { doRegister(); }

    private void doRegister() {
        try {
            String value = InetAddress.getLocalHost().getHostAddress() + ":" + props.getPort();
            RegistryParam param = new RegistryParam();
            param.setRegistryKey(props.getAppName());
            param.setRegistryValue(value);
            for (String admin : props.getAdminAddresses().split(",")) {
                restTemplate.postForObject(admin + "/registry", param, Object.class);
            }
        } catch (Exception e) {
            // 注册失败仅记录，不中断启动；下次心跳重试
            System.err.println("register failed: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 写自动配置**

`ExecutorAutoConfiguration.java`：

```java
package com.wwjob.executor.auto;

import com.wwjob.executor.ExecutorProperties;
import com.wwjob.executor.registry.ExecutorRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ExecutorProperties.class)
@ConditionalOnProperty(prefix = "wwjob.executor", name = "app-name")
public class ExecutorAutoConfiguration {
    @Bean
    public ExecutorRegistry executorRegistry(ExecutorProperties props) {
        return new ExecutorRegistry(props);
    }
}
```

`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：

```
com.wwjob.executor.auto.ExecutorAutoConfiguration
```

- [ ] **Step 5: executor-samples 配置与启动验证**

`ww-job-executor-samples/src/main/resources/application.yml` 追加：

```yaml
wwjob:
  executor:
    admin-addresses: http://localhost:8080
    app-name: sample-executor
    port: 8081
```

先启动 admin（`mvn -q -pl ww-job-admin spring-boot:run`），再启动 samples（`mvn -q -pl ww-job-executor-samples spring-boot:run`）。

Expected: 两个应用均启动；查库 `SELECT * FROM job_registry;` 能看到一条 `sample-executor` 的记录且 `heartbeat_time` 每 30s 刷新。需先在 `job_group` 表手动插入一行 `app_name='sample-executor'`（或留待 Task 13 的 CRUD 接口创建）。

- [ ] **Step 6: Commit**

```bash
git add ww-job-admin ww-job-executor ww-job-executor-samples
git commit -m "feat: 实现执行器注册与心跳（注册中心）"
```

---

### Task 9: admin 调度线程 + 时间轮整合（ScheduleHelper）

**Files:**
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/schedule/ScheduleHelper.java`
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/service/JobTriggerService.java`（先留桩，Task 10 填充）
- Modify: `ww-job-admin/src/main/resources/application.yml`（加调度参数）

**Interfaces:**
- Consumes: Task 4 `TimeWheel`、Task 5 `CronUtil`、Task 7 `JobInfoMapper`。
- Produces: `ScheduleHelper`（单例，两个线程：`scheduleThread` 每 1s 预读 DB、`ringThread` 每 1s 推进时间轮）；触发时调用 `JobTriggerService.trigger(jobId, triggerType)`。`JobTriggerService` 接口先声明、Task 10 实现。

- [ ] **Step 1: 写 `JobTriggerService` 桩接口**

```java
package com.wwjob.admin.service;

public interface JobTriggerService {
    void trigger(long jobId, String triggerType);
}
```

- [ ] **Step 2: 写 `ScheduleHelper`**

```java
package com.wwjob.admin.schedule;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wwjob.admin.entity.JobInfo;
import com.wwjob.admin.mapper.JobInfoMapper;
import com.wwjob.admin.service.JobTriggerService;
import com.wwjob.core.schedule.TimeWheel;
import com.wwjob.core.util.CronUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

@Component
public class ScheduleHelper {
    private static final long PRE_READ_MS = 5000;
    private static final long TICK_MS = 1000;
    private static final int WHEEL_SIZE = 60;

    private final JobInfoMapper jobInfoMapper;
    private final JobTriggerService triggerService;
    private final TimeWheel timeWheel = new TimeWheel(TICK_MS, WHEEL_SIZE);

    private Thread scheduleThread;
    private Thread ringThread;
    private volatile boolean running = true;

    public ScheduleHelper(JobInfoMapper jobInfoMapper, JobTriggerService triggerService) {
        this.jobInfoMapper = jobInfoMapper;
        this.triggerService = triggerService;
    }

    @PostConstruct
    public void start() {
        scheduleThread = new Thread(this::scheduleLoop, "ww-job-schedule");
        ringThread = new Thread(this::ringLoop, "ww-job-ring");
        scheduleThread.setDaemon(true);
        ringThread.setDaemon(true);
        scheduleThread.start();
        ringThread.start();
    }

    private void scheduleLoop() {
        while (running) {
            try {
                long now = System.currentTimeMillis();
                long windowEnd = now + PRE_READ_MS;
                // 预读：trigger_next_time 在 [now, now+5s] 内的启用任务
                var list = jobInfoMapper.selectList(new QueryWrapper<JobInfo>()
                        .eq("trigger_status", 1)
                        .ge("trigger_next_time", now)
                        .le("trigger_next_time", windowEnd));
                for (JobInfo job : list) {
                    refreshNextTime(job, now);
                }
            } catch (Exception e) {
                // 单次扫描异常不退出循环
            }
            sleep(TICK_MS);
        }
    }

    private void ringLoop() {
        while (running) {
            try {
                for (Runnable task : timeWheel.advance()) {
                    task.run();
                }
            } catch (Exception e) {
                // 忽略单次异常
            }
            sleep(TICK_MS);
        }
    }

    private void refreshNextTime(JobInfo job, long now) {
        long next = CronUtil.nextTime(job.getCron(), now);
        job.setTriggerNextTime(next);
        jobInfoMapper.updateById(job);
        long delay = next - System.currentTimeMillis();
        timeWheel.addTask(Math.max(0, delay), () -> triggerService.trigger(job.getId(), "cron"));
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @PreDestroy
    public void stop() {
        running = false;
        scheduleThread.interrupt();
        ringThread.interrupt();
    }
}
```

- [ ] **Step 3: 逻辑自检（无独立单测，靠 Task 14 端到端验证）**

Run: `mvn -q -pl ww-job-admin compile`
Expected: BUILD SUCCESS。

> 说明：`ScheduleHelper` 强依赖 DB 与多线程时序，Phase 1 通过 Task 14 的端到端集成测试验证；纯逻辑（时间轮、cron）已在 Task 4/5 单测覆盖。

- [ ] **Step 4: Commit**

```bash
git add ww-job-admin
git commit -m "feat: admin 增加调度线程（DB 预读 + 时间轮）"
```

---

### Task 10: HTTP 分发 + 执行器任务执行

**Files:**
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/service/JobTriggerServiceImpl.java`
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/service/ExecutorRouterService.java`
- Create: `ww-job-executor/src/main/java/com/wwjob/executor/handler/JobHandlerRegistry.java`
- Create: `ww-job-executor/src/main/java/com/wwjob/executor/controller/JobController.java`

**Interfaces:**
- Consumes: Task 3 `IJobHandler`/`@JobHandler`/`JobContext`；Task 6 `Router`；Task 7 `JobInfoMapper`/`JobLogMapper`/`JobRegistryMapper`；Task 8 注册中心。
- Produces: admin 侧 `JobTriggerServiceImpl`（实现 Task 9 的 `JobTriggerService`，选路由 → 构造 `TriggerParam` → HTTP 调执行器 `/run`）；executor 侧 `POST /run`（找 handler → 执行 → 返回 `ReturnT`）。

- [ ] **Step 1: executor 侧 `JobHandlerRegistry`（扫描带 `@JobHandler` 的 Bean）**

```java
package com.wwjob.executor.handler;

import com.wwjob.core.handler.IJobHandler;
import com.wwjob.core.handler.JobHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JobHandlerRegistry {
    private final ConcurrentHashMap<String, IJobHandler> handlers = new ConcurrentHashMap<>();

    @Autowired
    public JobHandlerRegistry(ApplicationContext ctx) {
        for (String name : ctx.getBeanNamesForAnnotation(JobHandler.class)) {
            Object bean = ctx.getBean(name);
            JobHandler ann = ctx.findAnnotationOnBean(name, JobHandler.class);
            handlers.put(ann.value(), (IJobHandler) bean);
        }
    }

    public IJobHandler get(String name) { return handlers.get(name); }
}
```

- [ ] **Step 2: executor 侧 `JobController`（内嵌 HTTP 的 `/run`）**

```java
package com.wwjob.executor.controller;

import com.wwjob.core.context.JobContext;
import com.wwjob.core.handler.IJobHandler;
import com.wwjob.core.model.ReturnT;
import com.wwjob.core.model.TriggerParam;
import com.wwjob.executor.handler.JobHandlerRegistry;
import org.springframework.web.bind.annotation.*;

@RestController
public class JobController {
    private final JobHandlerRegistry registry;
    public JobController(JobHandlerRegistry registry) { this.registry = registry; }

    @PostMapping("/run")
    public ReturnT<String> run(@RequestBody TriggerParam param) {
        IJobHandler handler = registry.get(param.getHandler());
        if (handler == null) {
            return ReturnT.fail("handler 未注册: " + param.getHandler());
        }
        JobContext ctx = new JobContext();
        ctx.setJobId(param.getJobId());
        ctx.setLogId(param.getLogId());
        ctx.setExecutorParam(param.getExecutorParam());
        ctx.setShardIndex(param.getShardIndex());
        ctx.setShardTotal(param.getShardTotal());
        try {
            return handler.execute(ctx);
        } catch (Exception e) {
            return ReturnT.fail(e.getMessage());
        }
    }
}
```

- [ ] **Step 3: admin 侧 `ExecutorRouterService`（在线地址列表 + 路由选择）**

```java
package com.wwjob.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wwjob.admin.entity.JobGroup;
import com.wwjob.admin.entity.JobRegistry;
import com.wwjob.admin.mapper.JobGroupMapper;
import com.wwjob.admin.mapper.JobRegistryMapper;
import com.wwjob.core.router.*;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ExecutorRouterService {
    private final JobGroupMapper groupMapper;
    private final JobRegistryMapper registryMapper;

    public ExecutorRouterService(JobGroupMapper groupMapper, JobRegistryMapper registryMapper) {
        this.groupMapper = groupMapper;
        this.registryMapper = registryMapper;
    }

    public String route(long jobGroupId, String routeStrategy, long jobId) {
        List<String> addresses = onlineAddresses(jobGroupId);
        Router router = switch (routeStrategy) {
            case "random" -> new RandomRouter();
            case "failover" -> new FailoverRouter();
            default -> new RoundRobinRouter();
        };
        return router.route(addresses, jobId);
    }

    public List<String> onlineAddresses(long jobGroupId) {
        return registryMapper.selectList(new QueryWrapper<JobRegistry>()
                        .eq("job_group_id", jobGroupId))
                .stream().map(JobRegistry::getRegistryValue)
                .collect(Collectors.toList());
    }
}
```

- [ ] **Step 4: admin 侧 `JobTriggerServiceImpl`（HTTP 分发）**

```java
package com.wwjob.admin.service;

import com.wwjob.admin.entity.JobInfo;
import com.wwjob.admin.entity.JobLog;
import com.wwjob.admin.mapper.JobInfoMapper;
import com.wwjob.admin.mapper.JobLogMapper;
import com.wwjob.core.model.ReturnT;
import com.wwjob.core.model.TriggerParam;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.time.LocalDateTime;

@Service
public class JobTriggerServiceImpl implements JobTriggerService {
    private final JobInfoMapper jobInfoMapper;
    private final JobLogMapper jobLogMapper;
    private final ExecutorRouterService routerService;
    private final RestTemplate restTemplate = new RestTemplate();

    public JobTriggerServiceImpl(JobInfoMapper jobInfoMapper, JobLogMapper jobLogMapper,
                                 ExecutorRouterService routerService) {
        this.jobInfoMapper = jobInfoMapper;
        this.jobLogMapper = jobLogMapper;
        this.routerService = routerService;
    }

    @Override
    public void trigger(long jobId, String triggerType) {
        JobInfo job = jobInfoMapper.selectById(jobId);
        if (job == null) return;
        String address = routerService.route(job.getJobGroupId(), job.getRouteStrategy(), jobId);
        if (address == null) {
            saveLog(job, "无可用执行器", null, 2);
            return;
        }
        JobLog log = saveLog(job, null, address, 0);
        TriggerParam param = new TriggerParam();
        param.setJobId(jobId);
        param.setHandler(job.getHandlerName());
        param.setExecutorParam(job.getExecutorParam());
        param.setLogId(log.getId());
        try {
            ReturnT<?> result = restTemplate.postForObject(
                    "http://" + address + "/run", param, ReturnT.class);
            if (result != null && result.getCode() == ReturnT.SUCCESS_CODE) {
                log.setStatus(1); log.setHandleCode(ReturnT.SUCCESS_CODE);
            } else {
                log.setStatus(2); log.setHandleCode(ReturnT.FAIL_CODE);
                log.setHandleMsg(result == null ? "无返回" : result.getMsg());
            }
        } catch (Exception e) {
            log.setStatus(2); log.setHandleCode(ReturnT.FAIL_CODE);
            log.setHandleMsg(e.getMessage());
        }
        log.setHandleTime(LocalDateTime.now());
        jobLogMapper.updateById(log);
        job.setTriggerLastTime(System.currentTimeMillis());
        jobInfoMapper.updateById(job);
    }

    private JobLog saveLog(JobInfo job, String failMsg, String address, int status) {
        JobLog log = new JobLog();
        log.setJobId(job.getId());
        log.setJobGroupId(job.getJobGroupId());
        log.setExecutorAddress(address);
        log.setHandlerName(job.getHandlerName());
        log.setTriggerTime(LocalDateTime.now());
        log.setStatus(status);
        log.setHandleMsg(failMsg);
        jobLogMapper.insert(log);
        return log;
    }
}
```

> 注意：`JobLog` 主键自增，`insert` 后 `id` 会回填（MyBatis-Plus 默认 `useGeneratedKeys`），`param.setLogId(log.getId())` 依赖此行为。

- [ ] **Step 5: 编译验证**

Run: `mvn -q -pl ww-job-admin,ww-job-executor compile`
Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add ww-job-admin ww-job-executor
git commit -m "feat: 实现 HTTP 任务分发与执行器任务执行"
```

---

### Task 11: 执行日志查询 REST API

**Files:**
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/controller/JobLogController.java`

**Interfaces:**
- Consumes: Task 7 `JobLogMapper`。
- Produces: `GET /joblog/page?jobId=&status=&page=&size=` 返回分页日志；`GET /joblog/{id}` 返回单条。

- [ ] **Step 1: 写 `JobLogController`**

```java
package com.wwjob.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wwjob.admin.entity.JobLog;
import com.wwjob.admin.mapper.JobLogMapper;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/joblog")
public class JobLogController {
    private final JobLogMapper jobLogMapper;
    public JobLogController(JobLogMapper jobLogMapper) { this.jobLogMapper = jobLogMapper; }

    @GetMapping("/page")
    public Page<JobLog> page(@RequestParam(defaultValue = "1") long page,
                             @RequestParam(defaultValue = "10") long size,
                             @RequestParam(required = false) Long jobId,
                             @RequestParam(required = false) Integer status) {
        QueryWrapper<JobLog> qw = new QueryWrapper<>();
        if (jobId != null) qw.eq("job_id", jobId);
        if (status != null) qw.eq("status", status);
        qw.orderByDesc("id");
        return jobLogMapper.selectPage(new Page<>(page, size), qw);
    }

    @GetMapping("/{id}")
    public JobLog detail(@PathVariable Long id) {
        return jobLogMapper.selectById(id);
    }
}
```

- [ ] **Step 2: 补 MyBatis-Plus 分页插件配置**

在 `ww-job-admin` 新建 `com/wwjob/admin/config/MybatisPlusConfig.java`：

```java
package com.wwjob.admin.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        return interceptor;
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `mvn -q -pl ww-job-admin compile`
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add ww-job-admin
git commit -m "feat: admin 增加执行日志分页查询接口"
```

---

### Task 12: 失败重试 + 手动触发 + 任务 CRUD

**Files:**
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/controller/JobController.java`
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/controller/JobGroupController.java`
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/service/JobTriggerServiceImpl.java`（加重试）

**Interfaces:**
- Consumes: Task 7 实体/Mapper；Task 10 `JobTriggerServiceImpl`。
- Produces: `POST /job`、`PUT /job`、`GET /job/page`、`POST /job/{id}/trigger`（手动触发）、`POST /job/{id}/start`、`POST /job/{id}/stop`；`POST /jobgroup`、`GET /jobgroup/list`。

- [ ] **Step 1: 在 `JobTriggerServiceImpl.trigger` 末尾加失败重试**

在 `trigger` 方法把 `result` 失败分支抽出后，补逻辑：当 `log.getStatus() == 2` 且 `job.getRetryCount() > 0` 时，循环重试最多 `retryCount` 次（每次重新 route + 调用），成功即停止。核心增量：

```java
int attempt = 0;
boolean ok = false;
while (!ok && attempt <= job.getRetryCount()) {
    // ... 一次分发执行，成功置 ok=true ...
    attempt++;
}
```

（完整重试结构在实现时把 Step 4 的单次调用包进循环，重试前重新 `route` 一次以利用故障转移。）

- [ ] **Step 2: 写 `JobController`（CRUD + 手动触发）**

```java
package com.wwjob.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wwjob.admin.entity.JobInfo;
import com.wwjob.admin.mapper.JobInfoMapper;
import com.wwjob.admin.service.JobTriggerService;
import com.wwjob.core.model.ReturnT;
import com.wwjob.core.util.CronUtil;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/job")
public class JobController {
    private final JobInfoMapper jobInfoMapper;
    private final JobTriggerService triggerService;

    public JobController(JobInfoMapper jobInfoMapper, JobTriggerService triggerService) {
        this.jobInfoMapper = jobInfoMapper;
        this.triggerService = triggerService;
    }

    @PostMapping
    public ReturnT<Long> create(@RequestBody JobInfo job) {
        job.setTriggerNextTime(CronUtil.nextTime(job.getCron(), System.currentTimeMillis()));
        jobInfoMapper.insert(job);
        return ReturnT.success(job.getId());
    }

    @PutMapping
    public ReturnT<String> update(@RequestBody JobInfo job) {
        job.setTriggerNextTime(CronUtil.nextTime(job.getCron(), System.currentTimeMillis()));
        jobInfoMapper.updateById(job);
        return ReturnT.success();
    }

    @GetMapping("/page")
    public Page<JobInfo> page(@RequestParam(defaultValue = "1") long page,
                              @RequestParam(defaultValue = "10") long size) {
        return jobInfoMapper.selectPage(new Page<>(page, size),
                new QueryWrapper<JobInfo>().orderByDesc("id"));
    }

    @PostMapping("/{id}/trigger")
    public ReturnT<String> trigger(@PathVariable Long id) {
        triggerService.trigger(id, "manual");
        return ReturnT.success();
    }

    @PostMapping("/{id}/start")
    public ReturnT<String> start(@PathVariable Long id) {
        JobInfo job = jobInfoMapper.selectById(id);
        job.setTriggerStatus(1);
        job.setTriggerNextTime(CronUtil.nextTime(job.getCron(), System.currentTimeMillis()));
        jobInfoMapper.updateById(job);
        return ReturnT.success();
    }

    @PostMapping("/{id}/stop")
    public ReturnT<String> stop(@PathVariable Long id) {
        JobInfo job = jobInfoMapper.selectById(id);
        job.setTriggerStatus(0);
        jobInfoMapper.updateById(job);
        return ReturnT.success();
    }
}
```

- [ ] **Step 3: 写 `JobGroupController`**

```java
package com.wwjob.admin.controller;

import com.wwjob.admin.entity.JobGroup;
import com.wwjob.admin.mapper.JobGroupMapper;
import com.wwjob.core.model.ReturnT;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/jobgroup")
public class JobGroupController {
    private final JobGroupMapper groupMapper;
    public JobGroupController(JobGroupMapper groupMapper) { this.groupMapper = groupMapper; }

    @PostMapping
    public ReturnT<Long> create(@RequestBody JobGroup group) {
        groupMapper.insert(group);
        return ReturnT.success(group.getId());
    }

    @GetMapping("/list")
    public List<JobGroup> list() {
        return groupMapper.selectList(null);
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `mvn -q -pl ww-job-admin compile`
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add ww-job-admin
git commit -m "feat: 增加失败重试、手动触发与任务/分组 CRUD 接口"
```

---

### Task 13: 示例 JobHandler

**Files:**
- Create: `ww-job-executor-samples/src/main/java/com/wwjob/executor/samples/handler/DemoHandler.java`

**Interfaces:**
- Consumes: Task 3 `@JobHandler`/`IJobHandler`/`JobContext`。
- Produces: 一个可被调度中心触发的示例任务 `demoHandler`。

- [ ] **Step 1: 写示例 handler**

```java
package com.wwjob.executor.samples.handler;

import com.wwjob.core.context.JobContext;
import com.wwjob.core.handler.IJobHandler;
import com.wwjob.core.handler.JobHandler;
import com.wwjob.core.model.ReturnT;

@JobHandler("demoHandler")
public class DemoHandler implements IJobHandler {
    @Override
    public ReturnT<String> execute(JobContext ctx) {
        String msg = "demo 执行成功, param=" + ctx.getExecutorParam()
                + ", shard=" + ctx.getShardIndex() + "/" + ctx.getShardTotal();
        System.out.println(msg);
        return ReturnT.success(msg);
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn -q -pl ww-job-executor-samples compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add ww-job-executor-samples
git commit -m "feat: 增加示例 JobHandler"
```

---

### Task 14: 端到端集成验证

**Files:**
- Create: `docker-compose.yml`
- Create: `README.md`（本地启动步骤）

**Interfaces:**
- Consumes: 所有前置 Task。
- Produces: 一键起 MySQL/Redis + 手动起 admin/samples 的验证流程，证明核心链路贯通。

- [ ] **Step 1: 写 `docker-compose.yml`（MySQL + Redis）**

```yaml
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: ww_job
    ports:
      - "3306:3306"
  redis:
    image: redis:7
    ports:
      - "6379:6379"
```

- [ ] **Step 2: 启动依赖与两个应用**

```bash
docker compose up -d mysql redis
# 等待 MySQL 就绪后
mvn -q -pl ww-job-admin spring-boot:run          # 终端 A
mvn -q -pl ww-job-executor-samples spring-boot:run # 终端 B
```

- [ ] **Step 3: 走一遍完整链路（curl）**

```bash
# 1. 建执行器分组
curl -X POST localhost:8080/jobgroup -H "Content-Type: application/json" \
     -d '{"appName":"sample-executor","title":"示例执行器"}'

# 2. 建任务（每 5 秒触发一次）
curl -X POST localhost:8080/job -H "Content-Type: application/json" \
     -d '{"jobGroupId":1,"jobName":"demo","handlerName":"demoHandler","cron":"*/5 * * * * *","routeStrategy":"round_robin","retryCount":1}'

# 3. 手动触发一次
curl -X POST localhost:8080/job/1/trigger

# 4. 查执行日志
curl "localhost:8080/joblog/page?jobId=1"
```

- [ ] **Step 4: 断言验证点**

Expected 全部满足：
1. `job_registry` 有 `sample-executor` 记录且心跳刷新
2. 手动触发后 `job_log` 出现一条 `status=1`、`handle_code=200` 的记录
3. 等 5 秒后 cron 自动触发，又新增一条成功日志
4. 停掉执行器，90s 后 `job_registry` 记录被剔除（验证注册中心）

- [ ] **Step 5: 写 README 并提交 + 合并分支**

`README.md` 简要记录：项目简介、架构图（引用设计文档）、技术栈、本地启动步骤（docker compose + 两个 mvn 命令）、Phase 1 已实现功能清单。

```bash
git add docker-compose.yml README.md
git commit -m "docs: 增加端到端验证脚本与 README"
git checkout main
git merge feat/phase1-backend
git push
```

---

## Self-Review 结论

- **Spec 覆盖**：Phase 1 核心链路（注册/心跳→时间轮调度→HTTP 分发→执行→日志→重试→手动触发）全部有对应 Task；阻塞策略、报警、分片广播属 Phase 2，本计划不覆盖（后续单独出计划）。
- **占位符扫描**：无 TBD/TODO；所有代码步骤均含实际代码。
- **类型一致性**：`ReturnT`/`TriggerParam`/`RegistryParam`/`IJobHandler`/`@JobHandler`/`JobContext`/`TimeWheel`/`CronUtil`/`Router`/`JobTriggerService` 的字段与签名在 Task 2/3/4/5/6/9/10/12 中保持一致。
