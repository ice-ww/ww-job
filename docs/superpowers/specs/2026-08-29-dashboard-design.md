# ww-job 概览仪表盘页设计

> 日期：2026-08-29
> 状态：设计已对齐，待实现
> 背景：控制台已有「任务管理 / 执行日志 / 执行器列表」三页，但缺一个「一眼看全局」的首页。本次新增**概览仪表盘页**：顶部统计卡片（任务/执行器/今日触发与结果）+ 今日失败 TOP 任务，卡片可点击跳转对应筛选页。

---

## 1. 现状与目标

**现状**：进控制台默认落在任务列表；任务数、执行器在线数、今日触发成败只能分别进各页或查库，无聚合视角。

**目标**：
1. **后端**新增聚合接口 `GET /dashboard/stats`，一次返回首页全部指标（任务、执行器、今日日志统计、失败 TOP）。
2. **前端**新增 `/dashboard` 概览页：统计卡片 + 失败 TOP 表，卡片点击跳转；`/` 首页改指向概览页。
3. 前端 30s 轮询 + 手动刷新。

**协作分工（沿用约定）**：前端代码由 Claude 代写；**后端聚合接口由用户自研**（参考实现见 §3）。

---

## 2. 关键决策记录

| # | 决策点 | 结论 |
| --- | --- | --- |
| D1 | 接口形态 | 新建 `DashboardController` + `DashboardService`，**单个聚合接口 `GET /dashboard/stats`** 返回 `DashboardStats` DTO（含 `failTop` 子列表）。一请求渲染整页，免多次往返 |
| D2 | 统计口径 | **今日** = `job_log.trigger_time >= 当天 00:00`（后端 `LocalDate.now().atStartOfDay()`，与存储的 LocalDateTime 同时区）；**在线执行器** = `heartbeat_time >= now-90s`（与 `RegistryCleaner.EXPIRE_SECONDS` 一致） |
| D3 | 失败 TOP | 今日 `status=2` 按 `job_id` 聚合、`COUNT(*)` 降序 `LIMIT 5`，`JOIN job_info` 取 `jobName` |
| D4 | 前端结构 | `views/Dashboard.vue` + `api/dashboard.js` + 路由 `/dashboard` + 菜单「概览」置首项；`/` 重定向 `/jobs` → `/dashboard`；vite proxy 补 `/dashboard` |
| D5 | 卡片跳转 | 任务总数→`/jobs`；在线执行器→`/registries`；今日成功/失败/未知→`/joblogs?status=1/2/3`（日志页已支持路由 query 预填）；失败 TOP 行点击→`/joblogs?jobId=N` |
| D6 | 刷新策略 | 30s `setInterval` 轮询 + 工具栏手动刷新按钮；`onUnmounted` 清理定时器 |

---

## 3. 后端 API（用户自研，参考实现）

新增 2 个 DTO、3 个 Mapper 聚合查询、1 个 Service、1 个 Controller。**新建 `dto` 包**（当前无此包）。

### 3.1 DTO

```java
// dto/DashboardStats.java
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
    // 无参构造 + getter/setter（风格参照 entity 包现有类）
}

// dto/FailTopItem.java
public class FailTopItem {
    private Long jobId;
    private String jobName;
    private String handlerName;
    private Long failCount;
    // 无参构造 + getter/setter
}
```

### 3.2 Mapper 聚合查询（加进现有 Mapper 接口）

`JobInfoMapper`：
```java
@Select("SELECT COUNT(*) FROM job_info")
long countAll();

@Select("SELECT COUNT(*) FROM job_info WHERE trigger_status = 1")
long countEnabled();
```

`JobRegistryMapper`：
```java
@Select("SELECT COUNT(*) FROM job_registry")
long countAll();

@Select("SELECT COUNT(*) FROM job_registry WHERE heartbeat_time >= #{threshold}")
long countOnline(@Param("threshold") LocalDateTime threshold);
```

`JobLogMapper`：
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

> 字段别名已与 `FailTopItem` 字段名一致，MyBatis-Plus 默认驼峰映射开启，无需额外配置。

### 3.3 Service + Controller

```java
// service/DashboardService.java
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

// controller/DashboardController.java
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

---

## 4. 前端工程变更（Claude 代写）

```
ww-job-web/
├── vite.config.js               # proxy + /dashboard
├── src/
│   ├── App.vue                  # 菜单加「概览」置首项（index=/dashboard）
│   ├── router/index.js          # + /dashboard 路由；'/' 重定向改 /dashboard
│   ├── api/dashboard.js         # 新增：getStats()
│   └── views/Dashboard.vue      # 新增：概览页
```

---

## 5. 页面设计（Dashboard.vue）

**布局**：
```
┌─ 概览 ────────────────────────────────────────────┐
│  [刷新]   （30s 自动刷新）                          │
│  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ │
│  │ 任务 │ │ 在线 │ │ 今日 │ │ 今日 │ │ 今日 │ │ 今日 │ │
│  │ 总数 │ │执行器│ │ 触发 │ │ 成功 │ │ 失败 │ │ 未知 │ │
│  │  12  │ │   2  │ │  45  │ │  40  │ │   3  │ │   2  │ │
│  └─────┘ └─────┘ └─────┘ └─────┘ └─────┘ └─────┘ │
│  ── 今日失败 TOP 5 ──────────────────────────────── │
│  │ 任务名    | JobHandler | 失败次数 | 操作          │
│  │ alert-fail | failDemo  | 3       | 查看日志       │
│  └────────────────────────────────────────────────┘
└─────────────────────────────────────────────────────┘
```

- **6 张统计卡**（`el-row` `:gutter="16"` + `el-col :span="4"`，每卡 `el-card` 居中大数字 + 灰色 label）：
  1. **任务总数**（主数字；副文字「启用 x · 停用 y」；点击 → `/jobs`）
  2. **在线执行器**（点击 → `/registries`；副文字「共 x 台注册」）
  3. **今日触发**（点击 → `/joblogs`）
  4. **今日成功**（绿主色，点击 → `/joblogs?status=1`）
  5. **今日失败**（红主色，点击 → `/joblogs?status=2`）
  6. **今日未知**（橙主色，点击 → `/joblogs?status=3`）
- **失败 TOP 表**：`el-table`（任务名 / JobHandler / 失败次数 / 操作「查看日志」→ `/joblogs?jobId=N`）；空数据显示「今日无失败任务」。
- **加载**：`onMounted` 加载 + `setInterval(load, 30000)`；`onUnmounted` 清理。
- **数字格式化**：卡片数字超 9999 用千分位（`toLocaleString()`）。

---

## 6. 联调与启动

1. admin(local) 启动 + 至少一台 executor + `ww-job-web` dev server。
2. 浏览器 `http://localhost:5173` → 默认落在概览页。
3. 卡片数据与 DB 实际值对照验证。

---

## 7. 边界（明确记录）

1. **不做图表库**（ECharts 等）：纯卡片 + 表格展示，YAGNI。后续要趋势图再引。
2. **聚合不缓存**：每次请求实时查库（数据量小）。
3. **今日=自然日**（0 点重置），不做跨日/滑动窗口。

---

## 8. 验证方案（端到端）

前置：admin(local) + executor(samples) + `ww-job-web` dev server。

| # | 场景 | 预期 |
| --- | --- | --- |
| 1 | 概览页加载 | 6 卡数据与 `job_info` / `job_registry` / `job_log` 实际数量一致；失败 TOP 为空时显示「今日无失败任务」 |
| 2 | 手动触发 demoHandler 任务 | 今日触发 +1、今日成功 +1（触发后轮询或手动刷新可见） |
| 3 | 手动触发 failDemoHandler 任务 | 今日失败 +1，失败 TOP 出现该任务（任务名/JOBHandler/次数） |
| 4 | 卡片点击跳转 | 今日失败卡 → `/joblogs?status=2`（日志页自动按失败筛选）；在线执行器卡 → `/registries` |
| 5 | TOP 行「查看日志」 | → `/joblogs?jobId=N`（按任务筛选） |
| 6 | `/` 首页重定向 | 浏览器开 `/` 落在 `/dashboard`，菜单「概览」高亮 |

### 实测记录

（待实现后回填）

---

## 9. 非目标（本次不做）

- 分组管理页、登录鉴权、告警历史页
- 趋势图 / 图表库、按日/周历史统计、告警次数统计
- 聚合结果缓存
