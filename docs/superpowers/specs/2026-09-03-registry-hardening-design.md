# 注册中心域加固 · 设计 spec（item 1 + item 3）

> 日期：2026-09-03 ｜ 状态：**已实现并验证（单 admin 范围）；双 admin 可选验证未跑** ｜ 前置：`docs/2026-09-03-robustness-priorities.md`（排序重议，本文档是第一工作流）
> 协作模式：后端改动由用户 ice-ww **自研编写**，本文档的 SQL/方法签名是**参考实现**；Claude 负责回归与验证。

## 背景与目标

按 backlog 排序原则（中小规模短板 = 故障语义/可观测性/健壮性），本工作流修两件事：

1. **写错数据**：`RegistryService.upsert` 是「select 再 insert/update」，两步间无锁无唯一键，两个并发心跳可插入重复节点；路由会把任务派给「已判离线但还没被 cleaner 清掉」的执行器（僵尸窗口最长 ~90s）。
2. **停机留僵尸**：executor 无下线通道，优雅停服/滚动重启也要等 90s 才被清。

**目标**：重复节点归零（唯一键 + 原子 upsert）；路由与广播只选 90s 内有心跳的节点；优雅停机节点立刻退出路由候选；Dashboard/cleaner/路由三处 90s 口径收敛为同一常量。

## 决策记录（已与 ice-ww 敲定）

- **Q1 `/registry/list` 语义不动**：前端列表页已按 90s 心跳自行判在线/离线，cleaner 每 10s 清行，list 天然 ≈ 近期。本次不碰 list 端点与前端 registry 页。
- **Q2 90s 用共享常量**：收敛三处各自写法（`RegistryCleaner` 私有 `EXPIRE_SECONDS`、`DashboardService` 内联 `minusSeconds(90)`、新路由过滤）。**不**现在就配置化（并入 backlog item 7）。
- **Q3 executor 验证不加 actuator 依赖**：admin 侧全自动 curl 可测；`@PreDestroy` 接线用一次交互 Ctrl+C 优雅停机确认广播收口。

## 现状核对（对过源码）

| 点 | 现状 | 证据 |
|---|---|---|
| upsert 无锁两段式 | selectOne(existing) → insert / updateById | RegistryService.java:41-56 |
| 无唯一键 | 仅 `KEY idx_key_time (registry_key, heartbeat_time)` | schema.sql:34-42 |
| 路由/广播不带新鲜度 | onlineAddresses = selectList(eq job_group_id) 全量 | ExecutorRouterService.java:42-47 |
| Dashboard 带新鲜度 | countOnline(now-90s) | DashboardService.java:40；JobRegistryMapper.java:19-20 |
| cleaner 口径 | 每 10s 清 `<90s`，常量私有 | RegistryCleaner.java:19,23-27 |
| executor 无下线通道 | register(@PostConstruct) + heartbeat(@Scheduled 30s)，无 unregister/@PreDestroy | ExecutorRegistry.java:19-49 |
| schema 初始化方式 | `spring.sql.init.mode: always` + `CREATE TABLE IF NOT EXISTS` → **对存量库不生效，须一次性 ALTER** | application.yml:6-9；schema.sql |
| `/registry/**` 免登 | AuthInterceptor 排除 `/registry/**` → 新增 `/registry/offline` 天然放行 | WebMvcConfig.java:27 |
| admin 无类级 @RequestMapping | RegistryController 方法级写完整字面路径 → 新端点必须 `@PostMapping("/registry/offline")`，写 `/offline` 会映射错（记录在案的老坑） | RegistryController.java |

## 正确性不变式（红绿灯）

1. **心跳幂等收敛**：同 `(job_group_id, registry_value)` 并发 upsert → 且仅一行。重复调用 /registry 不产生重复行。
2. **路由只选新鲜**：route 与 broadcast 只返回 90s 内有心跳的地址；心跳停止的节点自「最后一次心跳」起 ≤90s 退出候选（cleaner 每 10s 删行作为存储侧清理，二者独立互补）。
3. **offline 幂等**：节点不存在/已下线 → 仍返回 200。executor 优雅停机调用失败 → 静默（广播单台独立容错），kill -9 由不变式 2 兜底。
4. **口径唯一**：cleaner / dashboard / 路由三处 90s 读同一常量 `JobRegistry.ONLINE_SECONDS`，杜绝漂移。
5. **不破坏现有**：多 admin 共享 DB 语义（任意 admin 处理心跳等价，upsert 原子化后更强）；广播/failover 信道不变；手动地址组（address_type=1）不改。

## 阶段 A（admin 侧）— 唯一化 + 路由新鲜度

### A1 job_registry 唯一键 + 原子 upsert

**schema.sql**（新库生效）——job_registry 的 CREATE 里加一行：

```sql
UNIQUE KEY uk_group_value (job_group_id, registry_value)
```

**一次性迁移**（存量库：dev 3306 ww_job，及任何已建库）——**不能**塞进 schema.sql（mode: always 每启必跑，`IF NOT EXISTS` 会跳过但 ALTER 不会幂等，会炸）。存为 `ww-job-admin/src/main/resources/db/migrate/2026-09-03-registry-unique-key.sql`（spring sql init 只认 schema.sql，此文件不会被自动执行，仅留档手动跑）：

```sql
-- 一次性迁移：先清重再建唯一键（同 group+value 保留 id 最小行）
DELETE r1 FROM job_registry r1
  INNER JOIN job_registry r2
    ON r1.job_group_id = r2.job_group_id
   AND r1.registry_value = r2.registry_value
   AND r1.id > r2.id;
ALTER TABLE job_registry ADD UNIQUE KEY uk_group_value (job_group_id, registry_value);
```

**JobRegistryMapper**（补 import `org.apache.ibatis.annotations.Insert`）加：

```java
/** 原子 upsert：唯一键 (job_group_id, registry_value) 命中则刷新心跳，未命中则插入。并发心跳归一行。 */
@Insert("INSERT INTO job_registry (job_group_id, registry_key, registry_value, heartbeat_time) "
        + "VALUES (#{jobGroupId}, #{registryKey}, #{registryValue}, #{heartbeatTime}) "
        + "ON DUPLICATE KEY UPDATE heartbeat_time = VALUES(heartbeat_time), registry_key = VALUES(registry_key)")
int upsert(@Param("jobGroupId") long jobGroupId, @Param("registryKey") String registryKey,
           @Param("registryValue") String registryValue, @Param("heartbeatTime") LocalDateTime heartbeatTime);
```

**RegistryService.upsert** 收敛成单 SQL（删除 selectOne(existing) 分支；心跳写路径原两查一写 → 一查一写）：

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

> 说明：`ON DUPLICATE KEY UPDATE` 真实改到行 → `update_time` 由 `ON UPDATE CURRENT_TIMESTAMP` 自动刷新（命中时 heartbeat_time 必然变化，恒触发）。

### A2 路由/广播只选 90s 新鲜节点 + 口径常量化

**JobRegistry 实体**加（本仓库惯例：JobLog.STATUS_* 即活于实体）：

```java
/** 在线判定口径：心跳超过该秒数视为离线（cleaner / dashboard / 路由共用，避免口径漂移） */
public static final int ONLINE_SECONDS = 90;
```

**RegistryCleaner**：私有 `EXPIRE_SECONDS` 删除，改用 `JobRegistry.ONLINE_SECONDS`。
**DashboardService**：内联 `minusSeconds(90)` 改 `minusSeconds(JobRegistry.ONLINE_SECONDS)`。

**ExecutorRouterService.onlineAddresses**（补 import `java.time.LocalDateTime`；JobRegistry 已 import）加新鲜度过滤——route 与广播共用此方法，一次改动两处生效：

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

> 注：selectList 每次路由调用都会查库（现状已是如此）；job_group_id 是唯一键 `uk_group_value` 的前导列，走该索引前缀即可，规模内无需额外索引（见非目标）。

## 阶段 B（executor 侧）— 优雅下线

### B1 admin 收口端点

**RegistryController** 加（⚠️ 方法级必须写完整字面路径 `/registry/offline`，不能只写 `/offline`）：

```java
/** 执行器优雅下线：删注册行。幂等（0 行也成功）；kill -9 由 cleaner + 路由新鲜度兜底 */
@PostMapping("/registry/offline")
public ReturnT<String> offline(@RequestBody RegistryParam param) {
    return registryService.offline(param);
}
```

**RegistryService** 加 offline：

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

### B2 executor 停机广播

**ExecutorRegistry**：抽 `buildParam()`（register/heartbeat/unregister 复用），加 `@PreDestroy`（补 import `jakarta.annotation.PreDestroy`）：

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
```

> 说明：broadcast 同步串行，最坏 = admin 台数 × connect 3s，可接受；只覆盖优雅停机（Ctrl+C / 正常 close），kill -9 不走此路径 → 靠不变式 2 兜底。心跳照旧 30s，重启后 register 自愈加回。

## 非目标（本 spec 不做）

- `/registry/list` 语义收敛与前端 registry 页改动（决策 Q1）
- 手动地址组（address_type=1）接入路由候选（预存问题，单独确认）
- 90s 配置化（并入 backlog item 7）
- `(job_group_id, heartbeat_time)` 复合索引 / cleaner 批量优化
- 注册/心跳从 broadcast 改 failover（现行为保留）

## 交付物与验收清单

**后端（用户自研，参考如上）**
- [x] schema.sql job_registry 加唯一键 + migrate 迁移 SQL 文件
- [x] JobRegistryMapper.upsert
- [x] RegistryService：upsert 收敛单 SQL + offline
- [x] RegistryController `/registry/offline`
- [x] JobRegistry 实体 `ONLINE_SECONDS`；RegistryCleaner / DashboardService / ExecutorRouterService 改引用（onlineAddresses 加新鲜度过滤）
- [x] ExecutorRegistry：buildParam 抽取 + @PreDestroy unregister

**验证（Claude 执行，dev 3306 单 admin + 1 executor）**
- [x] 编译 admin + executor（T1-T6 全模块编译绿）
- [x] 心跳幂等：连续调 2 次 /registry 同 value → `SELECT COUNT(*) job_registry` 该 group+value 恒 1 行（并发 2 连发亦 1 行）——V1/V1b：连续 2 次 + 8 并发同 value 均 200 且恒 1 行
- [x] 路由新鲜度：SQL 把唯一在线行 `heartbeat_time` 调成 now-5min → 手动触发任务 → 落 `status=2 无可用执行器`（route 空，证明僵尸不再被派活）→ 调回后恢复可派——V2/V2b：陈旧心跳触发时行仍在、日志落「无可用执行器」，证明是新鲜度过滤而非 cleaner；恢复后触发落 status=1
- [x] offline：POST /registry/offline → list 无该行；重复 POST 仍 200（幂等）；等待下轮心跳 → 行自动加回（自愈）——V3 全绿
- [x] 优雅停机：交互 Ctrl+C 停 executor → admin list 该行消失（broadcast offline 生效），admin 日志无异常——V4 PASS，实测见下
- [ ] 双 admin 回归（可选）：任意台 admin 处理心跳/offline 等价，无重复行——（本次回归范围=单 admin dev，未跑）
- [ ] 多 admin 环境（8080+8082）路由语义不回归；executor 心跳走 broadcast 不受 offline 影响——（同上，未跑；代码改动未触及多 admin 路由路径，风险低）

**实测记录（2026-09-03，dev 3306 单 admin 8080 + executor samples 8081，job_registry group=1 value=127.0.0.1:8081）**
- 迁移：dev 3306 `ww_job` + loadtest 3307 `ww_job_loadtest` 均 0 重复行，DELETE no-op 后加 `uk_group_value (job_group_id, registry_value)`。
- V1b 并发 upsert：8 线程同 value（刷新既有行）+ 8 线程新 value（插入路径）→ 全部 200，各恒 1 行——原子 upsert 收敛生效。
- V2 决定性证明：把唯一在线行心跳调旧 100s 后手动 trigger，**触发瞬间行仍在（cleaner 未清）**但落 `status=2 无可用执行器`——僵尸不被派活由新鲜度过滤单独保证，非 cleaner 兜底。
- **V4 优雅停机排查（关键）**：首轮交互 Ctrl+C 判 FAIL——关机 30s+ 后行仍在、~90s 才被 RegistryCleaner 扫掉。**根因不是代码 bug**：samples `spring-boot:run` 从 local m2 解析 `ww-job-executor` 旧 jar（9-01 构建，**无 @PreDestroy**）；「编译绿 ≠ artifact 已更新」，executor 模块改动后必须先 `mvn install`。修正后：`mvn -pl ww-job-executor install` → `mvn -pl ww-job-executor-samples package` 打 boot jar → `java -jar` 直跑（不经 mvn fork）→ 交互 Ctrl+C 控制台出现 `broadcast failed: http://localhost:8082/registry/offline`（8082 宕机预期、8080 成功）→ admin 侧该行即刻消失（cleaner 物理下限≈关机后 60s，实测远快于此）→ @PreDestroy→offline 广播收口生效。
- 环境备注：Windows 下 `mvn spring-boot:run` fork 的 app JVM 收 Ctrl+C 不可靠（合成 CTRL_C、非 /F taskkill 均不触发 shutdown hooks）；验证优雅停机用 `java -jar`（信号直达 JVM），也贴合生产部署形态。

**迁移执行（Claude，改库前先与 ice-ww 确认）**：对 dev 3306 跑 migrate SQL（先 `SELECT` 数出重复行留档，再 DELETE+ALTER）；loadtest 容器如保留也跑一次。

## 参考

- 排序重议与现状核对：`docs/2026-09-03-robustness-priorities.md`
- 压测 F8-4 / registry 相关记录：`docs/load-test-report.md`（不在本次范围，只作口径参照）
- schema 初始化机制：`ww-job-admin/src/main/resources/application.yml:6-9`
