# 概览仪表盘页 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给前端控制台加一个「一眼看全局」的概览仪表盘页：统计卡片（任务/执行器/今日触发与结果）+ 今日失败 TOP 5，卡片可点击跳转对应筛选页，`/` 首页改指概览页。

**Architecture:** 后端新增一个聚合接口 `GET /dashboard/stats`（`DashboardController` + `DashboardService`，一次查 3 张表返回全部指标 + 失败 TOP 子列表，`@Select` 聚合 SQL 复用现有 mapper 模式）；前端新增 `/dashboard` 概览页（6 张统计卡 + 失败 TOP 表，30s 轮询），给 `JobLogList` 补 `status` 路由预填以兑现卡片跳转语义。

**Tech Stack:** 后端 Java 17 + Spring Boot 3.3 + MyBatis-Plus（`@Select` 文本块聚合 SQL）；前端 Vue 3 `<script setup>` + Element Plus + Vue Router + Axios（Vite dev proxy 到 admin:8080）。

**Spec:** [docs/superpowers/specs/2026-08-29-dashboard-design.md](../specs/2026-08-29-dashboard-design.md)

**协作分工（沿用约定，必须遵守）**：**T1 后端由用户自研**（本计划提供完整参考代码，用户抄写/理解后自行实现，Claude 只答疑）；**T2 前端由 Claude 代写**（用户明确「前端你帮我写，精力放后端」）。

## Global Constraints

1. 新增包 `com.wwjob.admin.dto`（当前无此包）；类风格照 `entity` 包（显式 getter/setter）。
2. 在线执行器口径：`heartbeat_time >= now-90s`（与 `RegistryCleaner.EXPIRE_SECONDS` 一致，spec D2）。
3. 今日口径：`job_log.trigger_time >= LocalDate.now().atStartOfDay()`（后端本地时区，spec D2）。
4. 失败 TOP：今日 `status=2` 按 `job_id` 聚合、`COUNT(*)` 降序 `LIMIT 5`、JOIN `job_info` 取 `jobName`（spec D3）。
5. 状态常量用 `JobLog.STATUS_SUCCESS/FAIL/UNKNOWN`（存在，勿硬编码数字）。
6. 前端轮询 30s、`onUnmounted` 清理定时器（复用 `RegistryList.vue` 现有模式）。
7. `JobLogList` 需补 `route.query.status` 预填（spec D5 承诺失败/成功/未知卡跳转后自动筛选；**当前只有 jobId 预填，这是计划补齐的出入，非 spec 缺陷**）。
8. 后端聚合不缓存、不做图表库（YAGNI，spec §7）。

---

### Task 1: 后端聚合接口 GET /dashboard/stats（用户自研）

**Files:**
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/dto/DashboardStats.java`
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/dto/FailTopItem.java`
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/service/DashboardService.java`
- Create: `ww-job-admin/src/main/java/com/wwjob/admin/controller/DashboardController.java`
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/mapper/JobInfoMapper.java`（+2 方法）
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/mapper/JobRegistryMapper.java`（+2 方法）
- Modify: `ww-job-admin/src/main/java/com/wwjob/admin/mapper/JobLogMapper.java`（+3 方法 +1 import）

**Interfaces:**
- Produces: `GET /dashboard/stats` 返回 JSON 结构：
  ```json
  { "jobTotal": 12, "jobEnabled": 5, "jobDisabled": 7,
    "executorTotal": 2, "executorOnline": 2,
    "logTotalToday": 45, "logSuccessToday": 40, "logFailToday": 3, "logUnknownToday": 2,
    "failTop": [ { "jobId": 1, "jobName": "x", "handlerName": "demoHandler", "failCount": 3 } ] }
  ```
- Consumes: 现有实体 `JobLog`（含 `STATUS_SUCCESS/FAIL/UNKNOWN` 常量）、`JobInfo`、`JobRegistry`；现有 `@MapperScan("com.wwjob.admin.mapper")`（已配置，新方法自动生效）。

**参考代码（用户抄写/理解后自研，逐文件）：**

- [x] **Step 1: 建 `dto` 包，写两个 DTO**

`DashboardStats.java`：
```java
package com.wwjob.admin.dto;

import java.util.List;

/** 概览仪表盘聚合统计 */
public class DashboardStats {
    private long jobTotal;
    private long jobEnabled;
    private long jobDisabled;
    private long executorTotal;
    private long executorOnline;
    private long logTotalToday;
    private long logSuccessToday;
    private long logFailToday;
    private long logUnknownToday;
    private List<FailTopItem> failTop;

    public DashboardStats() {
    }

    public long getJobTotal() { return jobTotal; }
    public void setJobTotal(long jobTotal) { this.jobTotal = jobTotal; }
    public long getJobEnabled() { return jobEnabled; }
    public void setJobEnabled(long jobEnabled) { this.jobEnabled = jobEnabled; }
    public long getJobDisabled() { return jobDisabled; }
    public void setJobDisabled(long jobDisabled) { this.jobDisabled = jobDisabled; }
    public long getExecutorTotal() { return executorTotal; }
    public void setExecutorTotal(long executorTotal) { this.executorTotal = executorTotal; }
    public long getExecutorOnline() { return executorOnline; }
    public void setExecutorOnline(long executorOnline) { this.executorOnline = executorOnline; }
    public long getLogTotalToday() { return logTotalToday; }
    public void setLogTotalToday(long logTotalToday) { this.logTotalToday = logTotalToday; }
    public long getLogSuccessToday() { return logSuccessToday; }
    public void setLogSuccessToday(long logSuccessToday) { this.logSuccessToday = logSuccessToday; }
    public long getLogFailToday() { return logFailToday; }
    public void setLogFailToday(long logFailToday) { this.logFailToday = logFailToday; }
    public long getLogUnknownToday() { return logUnknownToday; }
    public void setLogUnknownToday(long logUnknownToday) { this.logUnknownToday = logUnknownToday; }
    public List<FailTopItem> getFailTop() { return failTop; }
    public void setFailTop(List<FailTopItem> failTop) { this.failTop = failTop; }
}
```

`FailTopItem.java`：
```java
package com.wwjob.admin.dto;

/** 今日失败 TOP 任务 */
public class FailTopItem {
    private Long jobId;
    private String jobName;
    private String handlerName;
    private Long failCount;

    public FailTopItem() {
    }

    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }
    public String getJobName() { return jobName; }
    public void setJobName(String jobName) { this.jobName = jobName; }
    public String getHandlerName() { return handlerName; }
    public void setHandlerName(String handlerName) { this.handlerName = handlerName; }
    public Long getFailCount() { return failCount; }
    public void setFailCount(Long failCount) { this.failCount = failCount; }
}
```

> MyBatis 按列别名（`jobId/jobName/handlerName/failCount`）精确匹配属性名自动映射，无驼峰配置依赖。

- [ ] **Step 2: 三个 Mapper 各加聚合查询**

`JobInfoMapper.java`（现有 `selectByIdForUpdate` 不动，追加）：
```java
    @Select("SELECT COUNT(*) FROM job_info")
    long countAll();

    @Select("SELECT COUNT(*) FROM job_info WHERE trigger_status = 1")
    long countEnabled();
```

`JobRegistryMapper.java`（现有空接口，加 import `LocalDateTime` + `Param`）：
```java
    @Select("SELECT COUNT(*) FROM job_registry")
    long countAll();

    @Select("SELECT COUNT(*) FROM job_registry WHERE heartbeat_time >= #{threshold}")
    long countOnline(@Param("threshold") LocalDateTime threshold);
```

`JobLogMapper.java`（现有 3 个方法不动，追加 import `com.wwjob.admin.dto.FailTopItem`）：
```java
    @Select("SELECT COUNT(*) FROM job_log WHERE trigger_time >= #{from}")
    long countSince(@Param("from") LocalDateTime from);

    @Select("SELECT COUNT(*) FROM job_log WHERE trigger_time >= #{from} AND status = #{status}")
    long countByStatus(@Param("from") LocalDateTime from, @Param("status") int status);

    @Select("""
        SELECT l.job_id AS jobId, j.job_name AS jobName, l.handler_name AS handlerName, COUNT(*) AS failCount
        FROM job_log l JOIN job_info j ON l.job_id = j.id
        WHERE l.trigger_time >= #{from} AND l.status = 2
        GROUP BY l.job_id, j.job_name, l.handler_name
        ORDER BY failCount DESC
        LIMIT 5
        """)
    List<FailTopItem> selectFailTop(@Param("from") LocalDateTime from);
```

- [ ] **Step 3: 写 Service 聚合**

`DashboardService.java`：
```java
package com.wwjob.admin.service;

import com.wwjob.admin.dto.DashboardStats;
import com.wwjob.admin.entity.JobLog;
import com.wwjob.admin.mapper.JobInfoMapper;
import com.wwjob.admin.mapper.JobLogMapper;
import com.wwjob.admin.mapper.JobRegistryMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class DashboardService {
    private final JobInfoMapper jobInfoMapper;
    private final JobRegistryMapper registryMapper;
    private final JobLogMapper logMapper;

    public DashboardService(JobInfoMapper jobInfoMapper, JobRegistryMapper registryMapper, JobLogMapper logMapper) {
        this.jobInfoMapper = jobInfoMapper;
        this.registryMapper = registryMapper;
        this.logMapper = logMapper;
    }

    public DashboardStats stats() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime onlineThreshold = LocalDateTime.now().minusSeconds(90);
        long jobTotal = jobInfoMapper.countAll();
        long jobEnabled = jobInfoMapper.countEnabled();
        DashboardStats s = new DashboardStats();
        s.setJobTotal(jobTotal);
        s.setJobEnabled(jobEnabled);
        s.setJobDisabled(jobTotal - jobEnabled);
        s.setExecutorTotal(registryMapper.countAll());
        s.setExecutorOnline(registryMapper.countOnline(onlineThreshold));
        s.setLogTotalToday(logMapper.countSince(todayStart));
        s.setLogSuccessToday(logMapper.countByStatus(todayStart, JobLog.STATUS_SUCCESS));
        s.setLogFailToday(logMapper.countByStatus(todayStart, JobLog.STATUS_FAIL));
        s.setLogUnknownToday(logMapper.countByStatus(todayStart, JobLog.STATUS_UNKNOWN));
        s.setFailTop(logMapper.selectFailTop(todayStart));
        return s;
    }
}
```

- [x] **Step 4: 写 Controller**

`DashboardController.java`：
```java
package com.wwjob.admin.controller;

import com.wwjob.admin.dto.DashboardStats;
import com.wwjob.admin.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dashboard")
public class DashboardController {
    private final DashboardService dashboardService;
    public DashboardController(DashboardService dashboardService) { this.dashboardService = dashboardService; }

    @GetMapping("/stats")
    public DashboardStats stats() {
        return dashboardService.stats();
    }
}
```

- [x] **Step 5: 编译**

```bash
cd /d/javacode/ww-job
mvn -q -pl ww-job-admin -am compile
```
预期：BUILD SUCCESS。

- [x] **Step 6: 重启 admin(local) + curl 验证**

```bash
# 重启 admin（用户自行重启，保持 local profile）
curl http://localhost:8080/dashboard/stats
```
预期：JSON 字段齐全（jobTotal/jobEnabled/…/failTop），各数字与 DB 实际值一致（可 `SELECT COUNT(*) FROM job_info` 对照）。`failTop` 今日无失败时为 `[]`。

- [ ] **Step 7: Commit**

```bash
cd /d/javacode/ww-job
git add ww-job-admin/src/main/java/com/wwjob/admin/dto ww-job-admin/src/main/java/com/wwjob/admin/service/DashboardService.java ww-job-admin/src/main/java/com/wwjob/admin/controller/DashboardController.java ww-job-admin/src/main/java/com/wwjob/admin/mapper
git commit -m "feat: 概览仪表盘聚合接口 GET /dashboard/stats"
```

---

### Task 2: 前端概览页 /dashboard（Claude 代写）

**Files:**
- Create: `ww-job-web/src/api/dashboard.js`
- Create: `ww-job-web/src/views/Dashboard.vue`
- Modify: `ww-job-web/vite.config.js`（proxy + `/dashboard`）
- Modify: `ww-job-web/src/router/index.js`（`/` 重定向改 `/dashboard` + 新路由）
- Modify: `ww-job-web/src/App.vue`（菜单「概览」置首项）
- Modify: `ww-job-web/src/views/JobLogList.vue`（onMounted 补 `route.query.status` 预填）

**Interfaces:**
- Consumes: `GET /dashboard/stats` 响应（Task 1 JSON 结构）；现有 `request.js`（axios，baseURL `/`）；现有 `constants.js`（`LOG_STATUS`）；现有 `RegistryList.vue` 轮询模式。
- Produces: `/dashboard` 路由页面；卡片跳转 `/jobs`、`/registries`、`/joblogs?status=N`、`/joblogs?jobId=N`。

- [ ] **Step 1: 新建 API**

`ww-job-web/src/api/dashboard.js`：
```js
import request from './request'

export const getStats = () => request.get('/dashboard/stats')
```

- [ ] **Step 2: vite proxy 补 /dashboard**

`ww-job-web/vite.config.js`（`proxy` 对象加一行）：
```js
      '/dashboard': 'http://localhost:8080',
```

- [ ] **Step 3: 路由**

`ww-job-web/src/router/index.js` 全文替换：
```js
import { createRouter, createWebHistory } from 'vue-router'
import Dashboard from '../views/Dashboard.vue'
import JobList from '../views/JobList.vue'
import JobLogList from '../views/JobLogList.vue'
import RegistryList from '../views/RegistryList.vue'

const routes = [
  { path: '/', redirect: '/dashboard' },
  { path: '/dashboard', component: Dashboard },
  { path: '/jobs', component: JobList },
  { path: '/joblogs', component: JobLogList },
  { path: '/registries', component: RegistryList },
]

export default createRouter({ history: createWebHistory(), routes })
```

- [ ] **Step 4: 菜单加「概览」置首项**

`ww-job-web/src/App.vue`（`<el-menu>` 内第一项）：
```html
        <el-menu-item index="/dashboard">概览</el-menu-item>
```
`el-menu` 用 `:default-active="$route.path"`，`/` 重定向到 `/dashboard` 后菜单自动高亮「概览」。

- [ ] **Step 5: 新建 Dashboard.vue**

`ww-job-web/src/views/Dashboard.vue` 全文：
```vue
<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getStats } from '../api/dashboard'

const router = useRouter()
const stats = ref(null)
const loading = ref(false)

function fmt(n) {
  return (n ?? 0).toLocaleString()
}

const cards = [
  { key: 'jobTotal', label: '任务总数', to: '/jobs', sub: () => `启用 ${fmt(stats.value?.jobEnabled)} · 停用 ${fmt(stats.value?.jobDisabled)}` },
  { key: 'executorOnline', label: '在线执行器', to: '/registries', sub: () => `共 ${fmt(stats.value?.executorTotal)} 台注册` },
  { key: 'logTotalToday', label: '今日触发', to: '/joblogs' },
  { key: 'logSuccessToday', label: '今日成功', to: '/joblogs?status=1', color: '#67c23a' },
  { key: 'logFailToday', label: '今日失败', to: '/joblogs?status=2', color: '#f56c6c' },
  { key: 'logUnknownToday', label: '今日未知', to: '/joblogs?status=3', color: '#e6a23c' },
]

async function load() {
  loading.value = true
  try {
    const res = await getStats()
    stats.value = res.data
  } catch (e) {
    ElMessage.error('加载概览数据失败，请确认 admin 已启动')
  } finally {
    loading.value = false
  }
}

let timer = null
onMounted(() => {
  load()
  timer = setInterval(load, 30000) // 30s 轮询
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <el-card>
    <div class="toolbar">
      <el-button @click="load">刷新</el-button>
      <span class="hint">概览每 30s 自动刷新</span>
    </div>

    <el-row :gutter="16" v-loading="loading">
      <el-col v-for="c in cards" :key="c.key" :span="4">
        <el-card class="stat-card" shadow="hover" @click="router.push(c.to)">
          <div class="num" :style="c.color ? { color: c.color } : {}">{{ fmt(stats?.[c.key]) }}</div>
          <div class="label">{{ c.label }}</div>
          <div v-if="c.sub" class="sub">{{ c.sub() }}</div>
        </el-card>
      </el-col>
    </el-row>

    <div class="section-title">今日失败 TOP 5</div>
    <el-table :data="stats?.failTop || []" border stripe>
      <el-table-column label="任务ID" width="90" prop="jobId" />
      <el-table-column label="任务名" prop="jobName" min-width="160" show-overflow-tooltip />
      <el-table-column label="JobHandler" prop="handlerName" min-width="160" show-overflow-tooltip />
      <el-table-column label="失败次数" width="110" prop="failCount" />
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="router.push(`/joblogs?jobId=${row.jobId}`)">查看日志</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <span class="empty">今日无失败任务 🎉</span>
      </template>
    </el-table>
  </el-card>
</template>

<style scoped>
.toolbar { margin-bottom: 12px; display: flex; gap: 12px; align-items: center; }
.hint { color: #909399; font-size: 12px; }
.stat-card { cursor: pointer; text-align: center; }
.num { font-size: 28px; font-weight: 600; line-height: 1.2; }
.label { color: #909399; margin-top: 6px; font-size: 14px; }
.sub { color: #c0c4cc; margin-top: 8px; font-size: 12px; }
.section-title { margin: 20px 0 12px; font-weight: 600; color: #303133; }
.empty { color: #909399; }
</style>
```

- [ ] **Step 6: JobLogList 补 status 路由预填**

`ww-job-web/src/views/JobLogList.vue`，`onMounted` 内 `route.query.jobId` 判断之后加：
```js
  if (route.query.status !== undefined) {
    query.value.status = Number(route.query.status)
  }
```
（加在 `loadLogs()` 调用之前，`loadLogs` 会带上 `params.status`。）

- [ ] **Step 7: 前端构建验证**

```bash
cd /d/javacode/ww-job/ww-job-web
npm run build
```
预期：Vite build 成功（无语法错误/未解析 import）。

- [ ] **Step 8: Commit**

```bash
cd /d/javacode/ww-job
git add ww-job-web/src ww-job-web/vite.config.js
git commit -m "feat: 概览仪表盘页 /dashboard + 首页重定向 + 日志页 status 预填"
```

---

### Task 3: 端到端验证 + 文档 + 推送

**Files:**
- Modify: `README.md`（REST API 表 + 前端控制台行）
- Modify: `docs/superpowers/specs/2026-08-29-dashboard-design.md`（§8 实测记录回填）

- [ ] **Step 1: 启动三件套**

admin(local) 8080 + samples executor 8081 + `npm run dev` 5173 全在运行。

- [ ] **Step 2: 浏览器验证 6 场景（对照 spec §8 表）**

| # | 场景 | 预期 |
| --- | --- | --- |
| 1 | 打开 `http://localhost:5173` | 自动落在 `/dashboard`，菜单「概览」高亮；6 卡数字与 DB 一致；今日无失败时 TOP 表显示「今日无失败任务 🎉」 |
| 2 | 任务管理手动触发 demoHandler 任务 | 回概览刷新：今日触发 +1、今日成功 +1 |
| 3 | 任务管理手动触发 failDemoHandler 任务 | 回概览刷新：今日失败 +1、失败 TOP 出现该任务（任务名/handler/次数） |
| 4 | 点「今日失败」卡 | 跳 `/joblogs?status=2`，日志页**状态筛选自动为「失败」** |
| 5 | 点失败 TOP 行「查看日志」 | 跳 `/joblogs?jobId=N`，任务筛选自动为该任务 |
| 6 | 点「在线执行器」卡 | 跳 `/registries` 正常展示 |

- [ ] **Step 3: README 更新**

- REST API 表加一行：
  ```markdown
  | GET | `/dashboard/stats` | 概览统计（任务/执行器/今日日志/失败 TOP） |
  ```
- Phase 1 前端控制台行更新为：
  ```markdown
  - [x] 前端控制台（`ww-job-web`，Vue3 + Element Plus，概览仪表盘 / 任务管理 / 执行日志 / 执行器在线列表 + Cron 可视化配置）
  ```

- [ ] **Step 4: spec §8 实测记录回填**

在 `2026-08-29-dashboard-design.md` §8「实测记录」下如实记录各场景结果（含任何踩坑），未实测场景如实标注。

- [ ] **Step 5: 收尾 Commit + push**

```bash
cd /d/javacode/ww-job
git add README.md docs/superpowers/specs/2026-08-29-dashboard-design.md
git commit -m "docs: 概览仪表盘端到端验证记录 + README 更新"
git push -u origin main
```
预期：推送成功，远端 main 领先。

---

## 自审记录

- **Spec 覆盖**：spec D1-D6 全部有对应任务（D1→T1 接口/DTO/Service/Controller，D2/D3→T1 口径与 SQL，D4→T2 前端结构，D5→T2 卡片跳转 + T2 Step6 status 预填，D6→T2 30s 轮询）；spec §8 六场景→T3 Step2。
- **占位符扫描**：无 TBD/TODO；每段代码完整可运行。
- **类型一致**：后端 `DashboardStats` 字段名与前端 `stats?.[c.key]` 一一对应（jobTotal/jobEnabled/jobDisabled/executorTotal/executorOnline/logTotalToday/logSuccessToday/logFailToday/logUnknownToday/failTop）；`FailTopItem.jobId/jobName/handlerName/failCount` 与 `selectFailTop` 列别名一致；`JobLogList` 预填 `query.status`（number）与 `LOG_STATUS.value` 类型一致。
