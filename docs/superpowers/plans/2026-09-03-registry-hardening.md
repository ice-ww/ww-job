# 注册中心加固 · 实现计划（item 1 + item 3）

> **执行说明（本项目协作模式）**：所有后端改动由用户 ice-ww **自研编写**，本计划中的代码是**参考实现**（对照理解，不照抄即可）。Claude 负责维护测试工具、跑 spec「验收清单」回归与迁移 SQL 执行（**改库前先与 ice-ww 确认，先 SELECT 数重留档**）。步骤用 checkbox（`- [ ]`）跟踪。提交节奏由你决定（仅在你要求时 commit/push）。

**Goal:** 心跳并发不再写重（唯一键 + 原子 upsert）、路由/广播只选 90s 内新鲜节点、executor 优雅停机立即退出候选——三类「故障语义」缺陷一次收口。

**Architecture:** 改动全在注册中心域，两条链：
- **写路径（A1）**：`schema` 唯一键 `(job_group_id, registry_value)` + `JobRegistryMapper.upsert` 原子化（`INSERT ... ON DUPLICATE KEY UPDATE`）→ `RegistryService.upsert` 删掉「select 再 insert/updateById」的无锁两段式。
- **读路径（A2）+ 下线（B）**：`onlineAddresses` 加 90s 新鲜度过滤（route 单发与 broadcast 分片共用此方法，一处改动两处生效）；`/registry/offline` 端点 + executor `@PreDestroy` 广播下线。
- **口径（A2）**：`JobRegistry.ONLINE_SECONDS = 90` 共享常量，cleaner / dashboard / 路由三处收敛。

**Tech Stack:** Java 17、Spring Boot 3.3.5、MyBatis-Plus、MySQL（新增仅一个 `@Insert` 注解；DDL 走 schema.sql + 一次性迁移文件，无新增依赖）

**Spec:** `docs/superpowers/specs/2026-09-03-registry-hardening-design.md`（本文档每 Task 对应的验收证据在 spec「交付物与验收清单」，回归由 Claude 执行）

## Global Constraints

后端自研、计划代码为参考；只动注册中心域文件（见各 Task Files），绝不顺手改 `/registry/list`、前端 registry 页、手动地址组（address_type=1）。

**正确性不变式（红绿灯，任何一步不得破坏）：**
1. **心跳幂等收敛**：同 `(job_group_id, registry_value)` 并发 upsert → 且仅一行；重复调 `/registry` 不产生重复行。
2. **路由只选新鲜**：route 与 broadcast 只返回 90s 内有心跳的地址；心跳停止节点自最后一次心跳 ≤90s 退出候选（cleaner 每 10s 删行是存储侧互补清理，二者独立）。
3. **offline 幂等**：节点不存在/已下线 → 仍 200；executor 优雅停机广播失败 → 静默（broadcast 单台独立容错），kill -9 由不变式 2 兜底。
4. **口径唯一**：cleaner / dashboard / 路由的 90s 都读 `JobRegistry.ONLINE_SECONDS`。
5. **不破坏现有**：多 admin 共享 DB（任意 admin 处理心跳/offline 等价）；手动地址组不改；`/registry/list` 语义不动。

**文件边界（绝不动）：**
- `docs/superpowers/plans/2026-08-29-executor-admin-failover.md`、`docs/superpowers/plans/2026-08-30-auth-jwt.md`、`ww-job-executor/**/controller/JobController.java`（用户文件，不接触不提交）
- 任何代码/文档不出现真实 MySQL 密码 / QQ SMTP 授权码
- 迁移 ALTER **不能**塞进 `schema.sql`（`spring.sql.init.mode: always` + `IF NOT EXISTS` 只对空表生效，ALTER 每启必跑会炸）——单独存 `db/migrate/`，仅手动执行

**运行时前置**：Task 2 的迁移只对**新库**生效；存量 dev 库（3306 ww_job）必须先跑 migrate SQL（清重 + ALTER）再启动带 upsert 的新代码——该步由 Claude 在回归阶段执行，**先与 ice-ww 确认**。

---

### Task 1: 口径常量化 —— JobRegistry.ONLINE_SECONDS + 两处收敛

**Files:**
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/entity/JobRegistry.java`（类体顶部加常量，紧挨 `@TableName` 之后）
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/service/RegistryCleaner.java`（删私有 `EXPIRE_SECONDS`，改用常量；JobRegistry 已 import）
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/service/DashboardService.java`（:32 内联 `minusSeconds(90)` → 常量；需补 `import com.wwjob.admin.entity.JobRegistry;`，当前只 import 了 `entity.JobLog`）

**Interfaces:**
- Produces: `JobRegistry.ONLINE_SECONDS`（int，90）——Task 4 的路由新鲜度过滤继续引用

- [ ] **Step 1: JobRegistry 实体加常量**

```java
@TableName("job_registry")
public class JobRegistry {
    /** 在线判定口径：心跳超过该秒数视为离线（cleaner / dashboard / 路由共用，避免口径漂移） */
    public static final int ONLINE_SECONDS = 90;

    @TableId(type = IdType.AUTO)
    private Long id;
    ...
```

- [ ] **Step 2: RegistryCleaner 收敛**

删除字段 `private static final int EXPIRE_SECONDS = 90;`（:19），`clean()` 内（:25）：

```java
LocalDateTime threshold = LocalDateTime.now().minusSeconds(JobRegistry.ONLINE_SECONDS);
```

- [ ] **Step 3: DashboardService 收敛**

补 import 行后，:32 改：

```java
LocalDateTime onlineThreshold = LocalDateTime.now().minusSeconds(JobRegistry.ONLINE_SECONDS);
```

- [ ] **Step 4: 编译**

Run: `mvn -q -pl ww-job-admin -am compile`
Expected: BUILD SUCCESS（纯引用替换，无行为变化）

---

### Task 2: schema 唯一键 + 一次性迁移文件

**Files:**
- Modify: `ww-job-admin/src/main/resources/db/schema.sql`（job_registry CREATE 块）
- Create: `ww-job-admin/src/main/resources/db/migrate/2026-09-03-registry-unique-key.sql`

**Interfaces:**
- Produces: 唯一键 `uk_group_value (job_group_id, registry_value)` —— Task 3 的原子 upsert 的撞键前提；存量库手动迁移的完整 SQL（Claude 回归阶段先确认再执行）

- [ ] **Step 1: schema.sql 加唯一键**

job_registry 的 CREATE（schema.sql:34-42）改为（`UNIQUE KEY` 放 `KEY idx_key_time` 前）：

```sql
CREATE TABLE IF NOT EXISTS job_registry (
                                            id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                            job_group_id BIGINT NOT NULL,
                                            registry_key VARCHAR(64) NOT NULL COMMENT 'appName',
    registry_value VARCHAR(128) NOT NULL COMMENT 'ip:port',
    heartbeat_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_group_value (job_group_id, registry_value),
    KEY idx_key_time (registry_key, heartbeat_time)
    ) COMMENT '执行器注册表';
```

> `spring.sql.init.mode: always` + `IF NOT EXISTS`：新库建表即带唯一键。**存量库不生效**，走 Step 2。

- [ ] **Step 2: 建迁移文件**（新建目录 `db/migrate/`）

```sql
-- 一次性迁移（存量库手动执行，勿放入 schema.sql）：job_registry 加唯一键 (job_group_id, registry_value)。
-- 执行顺序必须：① 先清重（同 group+value 保留 id 最小行）② 再加唯一键。顺序反了会因脏数据建键失败。
-- 回归阶段由 Claude 先 SELECT COUNT(*) 数出将删行数留档，再整段执行（与 ice-ww 确认后）。
DELETE r1 FROM job_registry r1
  INNER JOIN job_registry r2
    ON r1.job_group_id = r2.job_group_id
   AND r1.registry_value = r2.registry_value
   AND r1.id > r2.id;
ALTER TABLE job_registry ADD UNIQUE KEY uk_group_value (job_group_id, registry_value);
```

- [ ] **Step 3: 确认编译不受影响（无 Java 代码改动，可跳过；若跑）**

Run: `mvn -q -pl ww-job-admin -am compile`
Expected: BUILD SUCCESS

> ⚠️ 本 Task **不在本地库执行**。dev 3306 的 ALTER 属于 Claude 回归阶段动作，改库前会先向你确认。

---

### Task 3: JobRegistryMapper.upsert 原子化 + RegistryService.upsert 收敛

**Files:**
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/mapper/JobRegistryMapper.java`（import 区补 `org.apache.ibatis.annotations.Insert`；现 imports 仅 `Param`/`Select`）
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/service/RegistryService.java`（:41-56 的 selectOne(existing)→insert/updateById 分支整段替换）

**Interfaces:**
- Consumes: Task 2 的唯一键 `uk_group_value`（运行期）；`JobGroup`/`groupMapper`（现有）
- Produces: `int upsert(jobGroupId, registryKey, registryValue, heartbeatTime)`（JobRegistryMapper）；删掉 service 层现有 `JobRegistry existing = ...` 查询

- [ ] **Step 1: Mapper 加 upsert**

```java
/** 原子 upsert：唯一键 (job_group_id, registry_value) 命中则刷新心跳，未命中则插入。并发心跳归一行。 */
@Insert("INSERT INTO job_registry (job_group_id, registry_key, registry_value, heartbeat_time) "
        + "VALUES (#{jobGroupId}, #{registryKey}, #{registryValue}, #{heartbeatTime}) "
        + "ON DUPLICATE KEY UPDATE heartbeat_time = VALUES(heartbeat_time), registry_key = VALUES(registry_key)")
int upsert(@Param("jobGroupId") long jobGroupId, @Param("registryKey") String registryKey,
           @Param("registryValue") String registryValue, @Param("heartbeatTime") LocalDateTime heartbeatTime);
```

> MySQL ≥ 8.0.20 对 `VALUES()` 打 deprecation 告警但功能正常；本库若为 8.0.19+ 想零告警可改 `AS new ON DUPLICATE KEY UPDATE heartbeat_time = new.heartbeat_time`，二选一均可，实现按你库版本定。
> 命中时 `heartbeat_time` 必然被刷成新 now → `update_time` 由 `ON UPDATE CURRENT_TIMESTAMP` 自动刷新。

- [ ] **Step 2: RegistryService.upsert 收敛**（删掉 selectOne(existing) 分支 + insert/updateById 双写路径）

```java
private ReturnT<String> upsert(RegistryParam param) {
    JobGroup group = groupMapper.selectOne(
            new QueryWrapper<JobGroup>().eq("app_name", param.getRegistryKey()));
    if (group == null) {
        return ReturnT.fail("执行器分组未注册: " + param.getRegistryKey());
    }
    registryMapper.upsert(group.getId(), param.getRegistryKey(),
            param.getRegistryValue(), LocalDateTime.now());
    return ReturnT.success();
}
```

> 心跳写路径 两查一写 → 一查一写。`JobRegistry` 的 import 此刻暂时无引用（Task 5 的 offline 会再用回，属预期，勿删）。

- [ ] **Step 3: 编译**

Run: `mvn -q -pl ww-job-admin -am compile`
Expected: BUILD SUCCESS（`LocalDateTime`/`QueryWrapper`/`ReturnT` import 均已在）

---

### Task 4: ExecutorRouterService.onlineAddresses 加新鲜度过滤

**Files:**
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/service/ExecutorRouterService.java`（:42-47 onlineAddresses；import 区补 `java.time.LocalDateTime`，现仅 `List`/`Collectors`；`JobRegistry` 已 import）

**Interfaces:**
- Consumes: Task 1 的 `JobRegistry.ONLINE_SECONDS`
- Produces: 过滤后的 `onlineAddresses` —— route 单发（:33）与 broadcast 分片（JobTriggerServiceImpl.java:94）共用，两处同步受益

- [ ] **Step 1: 替换 onlineAddresses 方法体**

```java
public List<String> onlineAddresses(long jobGroupId) {
    LocalDateTime threshold = LocalDateTime.now().minusSeconds(JobRegistry.ONLINE_SECONDS);
    return registryMapper.selectList(new QueryWrapper<JobRegistry>()
                    .eq("job_group_id", jobGroupId)
                    .ge("heartbeat_time", threshold))
            .stream().map(JobRegistry::getRegistryValue)
            .collect(Collectors.toList());
}
```

> 心跳停止的节点：cleaner 至多 10s 删行，但删行前的每个调度周期 route 都会读到它——本过滤把「已判离线未清」的僵尸地址关在路由候选之外（窗口归零），cleaner 只管存储侧清理。`job_group_id` 是唯一键 `uk_group_value` 前导列，走索引前缀即可。

- [ ] **Step 2: 编译**

Run: `mvn -q -pl ww-job-admin -am compile`
Expected: BUILD SUCCESS

---

### Task 5: admin /registry/offline 收口端点

**Files:**
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/service/RegistryService.java`（加 offline 方法）
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/controller/RegistryController.java`（加端点）

**Interfaces:**
- Produces: `POST /registry/offline`（body = RegistryParam）——幂等删行，Task 6 executor `@PreDestroy` 的调用目标；该端点被 AuthInterceptor 排除的 `/registry/**` 天然放行

- [ ] **Step 1: RegistryService 加 offline**（复用 group 查询；删 0 行也算成功 = 幂等）

```java
public ReturnT<String> offline(RegistryParam param) {
    JobGroup group = groupMapper.selectOne(
            new QueryWrapper<JobGroup>().eq("app_name", param.getRegistryKey()));
    if (group == null) {
        return ReturnT.fail("执行器分组未注册: " + param.getRegistryKey());
    }
    registryMapper.delete(new QueryWrapper<JobRegistry>()
            .eq("job_group_id", group.getId())
            .eq("registry_value", param.getRegistryValue()));
    return ReturnT.success();
}
```

> 此方法用回 `JobRegistry` 泛型 → Task 3 里"暂时无引用"的 import 在此恢复使用。

- [ ] **Step 2: RegistryController 加端点**（⚠️ RegistryController **无类级 @RequestMapping**——必须写完整字面路径 `/registry/offline`，写 `/offline` 会映射错，老坑）

```java
@PostMapping("/registry/offline")
public ReturnT<String> offline(@RequestBody RegistryParam param) {
    return registryService.offline(param);
}
```

- [ ] **Step 3: 编译**

Run: `mvn -q -pl ww-job-admin -am compile`
Expected: BUILD SUCCESS

---

### Task 6: ExecutorRegistry 抽 buildParam + @PreDestroy 优雅下线

**Files:**
- Modify: `ww-job-executor/src/main/java/com/wwjob/executor/registry/ExecutorRegistry.java`（import 区补 `jakarta.annotation.PreDestroy`；现 imports `PostConstruct` 等）

**Interfaces:**
- Consumes: Task 5 的 `POST /registry/offline`；`AdminAddressPool.broadcast(path, body)`（现签名不变）
- Produces: executor 停机时广播 `offline`（bean 为 `ExecutorAutoConfiguration` 的 @Bean 单例 → Spring 优雅关闭时销毁回调触发 @PreDestroy）

- [ ] **Step 1: 抽 buildParam + 重构 doRegister + 加 unregister**

register(@PostConstruct) / heartbeat(@Scheduled 30s) 仍走 `doRegister()`；载荷构建抽到 `buildParam()`，register/heartbeat/offline 三处共用：

```java
@PreDestroy
public void unregister() {
    try {
        // 优雅停机：广播下线（多 admin 每台独立容错；共享 DB，任一台生效即删行）
        adminPool.broadcast("/registry/offline", buildParam());
    } catch (Exception e) {
        System.err.println("unregister failed: " + e.getMessage());
    }
}

private void doRegister() {
    try {
        adminPool.broadcast("/registry", buildParam());
    } catch (Exception e) {
        System.err.println("register failed: " + e.getMessage());
    }
}

/** 注册/心跳/下线共用载荷：appName + ip:port（InetAddress 解析失败抛给调用方 try/catch） */
private RegistryParam buildParam() throws Exception {
    String ip = props.getAddress();
    if (ip == null || ip.isEmpty()) {
        ip = InetAddress.getLocalHost().getHostAddress();
    }
    return new RegistryParam(props.getAppName(), ip + ":" + props.getPort());
}
```

> 行为对照现状：原 doRegister 内联算 ip/拼 value（ip 解析失败落入 `catch` 打 "register failed"）——重构后解析在 buildParam、异常同样被调用方 catch 兜住，语义等价。下线广播最坏耗时 = admin 台数 × connect 3s（同步串行），可接受。

- [ ] **Step 2: 编译 executor**

Run: `mvn -q -pl ww-job-executor -am compile`
Expected: BUILD SUCCESS（core 的 `RegistryParam(String, String)` 构造器已存在，无需改）

---

## 落地顺序与回归（写完代码后）

1. **六 Task 完成 + 各自编译绿**后，把改动通知 Claude。
2. Claude 执行（与 ice-ww 确认后）：
   - **迁移**：dev 3306 先 `SELECT` 数出 job_registry 重复行留档 → 执行 `db/migrate/2026-09-03-registry-unique-key.sql` → `SHOW INDEX` 验证唯一键就位。
   - **编译 + 重启** admin / executor（loadtest profile）。
   - **回归**：按 spec「验证（Claude 执行）」跑——
     - 心跳幂等（连续 2 次 + 并发 2 连发 → COUNT 恒 1）
     - 路由新鲜度（SQL 把心跳调旧 → 触发落「无可用执行器」→ 调回恢复）
     - offline 幂等 + 自愈（删行后等心跳回来）
     - 优雅停机（Ctrl+C executor → admin list 该行消失）
     - 双 admin 回归（可选）
3. **留档与提交**：回归通过后，按你要求才 commit/push；spec「交付物」勾选回填。

## 参考

- 设计 spec：`docs/superpowers/specs/2026-09-03-registry-hardening-design.md`（正确性不变式 / 非目标 / 验收清单权威版本）
- 排序重议：`docs/2026-09-03-robustness-priorities.md`
